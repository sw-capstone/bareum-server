package com.bareum.server.domain.report.service.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.domain.report.body.ValidatedReportBody;
import com.bareum.server.domain.report.config.ReportIntakeProperties;
import com.bareum.server.domain.report.config.ReportUploadProperties;
import com.bareum.server.domain.report.dto.response.ReportUploadResponse;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.entity.ReportIntakeRequest;
import com.bareum.server.domain.report.entity.ReportParseAttempt;
import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.enums.ParseStatus;
import com.bareum.server.domain.report.enums.ReportIntakeStatus;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.ReportIntakePolicy;
import com.bareum.server.domain.report.intake.ReportIntakeResult;
import com.bareum.server.domain.report.repository.ReportContentRepository;
import com.bareum.server.domain.report.repository.ReportIntakeRequestRepository;
import com.bareum.server.domain.report.repository.ReportParseAttemptRepository;
import com.bareum.server.domain.report.repository.ReportRepository;
import com.bareum.server.domain.report.source.UploadedReportFile;

import tools.jackson.databind.json.JsonMapper;

/** 접수 선점, 파싱 상태와 결과 저장, 만료 기록 삭제를 쓰기 트랜잭션으로 처리한다. */
@Service
@Transactional
public class ReportIntakeCommandService {
	private final MemberRepository members;
	private final ReportIntakeRequestRepository requests;
	private final ReportRepository reports;
	private final ReportParseAttemptRepository attempts;
	private final ReportContentRepository contents;
	private final Clock clock;
	private final ReportUploadProperties properties;
	private final ReportIntakeProperties intakeProperties;
	private final JsonMapper mapper = JsonMapper.builder().build();

	public ReportIntakeCommandService(
		MemberRepository members,
		ReportIntakeRequestRepository requests,
		ReportRepository reports,
		ReportParseAttemptRepository attempts,
		ReportContentRepository contents,
		Clock clock,
		ReportUploadProperties properties,
		ReportIntakeProperties intakeProperties
	) {
		this.members = members;
		this.requests = requests;
		this.reports = reports;
		this.attempts = attempts;
		this.contents = contents;
		this.clock = clock;
		this.properties = properties;
		this.intakeProperties = intakeProperties;
	}

	public Reservation reserve(long memberId, UUID key, String fingerprint, String objectKey) {
		// 사용자 행을 짧게 잠가 첫 요청의 동시 INSERT도 한 번만 수행한다.
		var member = members.findByIdForUpdate(memberId)
				.filter(m -> m.getStatus() == MemberStatus.ACTIVE && m.getWithdrawnAt() == null)
				.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN));
		var existing = requests.findForUpdate(memberId, key);
		// 조회 이후에 들어온 동시 요청도 선점 잠금 안에서 다시 확인한다.
		if (existing.isPresent()) {
			var request = existing.get();
			request.requireMatchingFingerprint(fingerprint);
			request.requireReplayable();
			return new Reservation(request.getId(), request.getSourceObjectKey(), result(request));
		}
		var now = clock.instant();
		var request =
				ReportIntakeRequest.reserve(member, key, fingerprint, now, now.plus(properties.processingTimeout()));
		request.assignSourceKey(objectKey);
		requests.save(request);
		return new Reservation(request.getId(), objectKey, null);
	}

	public void recordSource(long requestId, UploadedReportFile source) {
		var request = locked(requestId);
		request.requireProcessingAt(clock.instant());
		request.recordUploadedSource(source);
	}

	public ParseWork startParsing(long requestId, ReportType type, UploadedReportFile source) {
		var request = locked(requestId);
		request.requireProcessingAt(clock.instant());
		var report = reports.save(Report.createFromFile(request.getMember(), type, source));
		var attempt = attempts.save(ReportParseAttempt.request(report));
		attempt.start(clock.instant());
		request.attachAttempt(attempt);
		return new ParseWork(requestId, report.getId(), attempt.getId(), request.getProcessingDeadlineAt());
	}

	/** 직전 실패와 처리 기한, 재시도 한도를 확인한 뒤 같은 원본의 새 파싱 시도를 저장한다. */
	public ParseWork retryParsing(ParseWork work, String errorCode) {
		var request = locked(work.requestId());
		request.requireProcessingAt(clock.instant());
		requireAttemptIdentity(request, work);
		if (request.getParseAttempt().getProcessStatus() != ParseStatus.FAILED
				|| !errorCode.equals(request.getParseAttempt().getErrorCode())) {
			throw new ReportException(ReportErrorCode.INVALID_INTAKE_STATE);
		}
		if (attempts.countByReportId(work.reportId()) >= 1
				+ ReportIntakePolicy.MAX_ADDITIONAL_PARSE_ATTEMPTS) {
			throw new ReportException(ReportErrorCode.INVALID_INTAKE_STATE);
		}
		// 활성 시도 유니크 인덱스가 새 INSERT보다 먼저 이전 시도의 종료를 보도록 한다.
		attempts.flush();
		var attempt = attempts.save(ReportParseAttempt.request(request.getReport()));
		attempt.start(clock.instant());
		request.attachAttempt(attempt);
		return new ParseWork(work.requestId(), work.reportId(), attempt.getId(), request.getProcessingDeadlineAt());
	}

	/** 재시도 대기 중 서버가 종료돼도 원인이 남도록 현재 파싱 시도의 실패를 먼저 저장한다. */
	public void recordRetryableFailure(ParseWork work, String errorCode) {
		var request = locked(work.requestId());
		request.requireProcessingAt(clock.instant());
		requireAttempt(request, work);
		request.getParseAttempt().fail(errorCode, clock.instant());
	}

	/** 외부 호출 전에 접수 기한과 현재 파싱 시도의 식별자, 진행 상태를 잠금 안에서 확인한다. */
	public void requireParsingActive(ParseWork work) {
		var request = locked(work.requestId());
		request.requireProcessingAt(clock.instant());
		requireAttempt(request, work);
	}

	/** 복구로 종료된 접수와 늦은 응답을 차단하고 유효한 파싱 결과만 현재 본문과 완료 응답으로 저장한다. */
	public ReportIntakeResult complete(ParseWork work, ValidatedReportBody body, ExtractionQuality quality) {
		var request = locked(work.requestId());
		if (request.getStatus() != ReportIntakeStatus.PROCESSING) {
			request.requireReplayable();
			return result(request);
		}
		if (!clock.instant().isBefore(request.getProcessingDeadlineAt())) {
			return failLocked(request, ReportErrorCode.INTAKE_PROCESSING_TIMEOUT,
					ReportErrorCode.INTAKE_PROCESSING_TIMEOUT.getCode(), properties.uncertainCleanupDelay());
		}
		requireAttempt(request, work);
		var content = contents.save(ReportContent.create(request.getReport(), body));
		content.adoptAsCurrent();
		request.getParseAttempt()
				.complete(content, quality, mapper.valueToTree(body.getBody().warnings()), clock.instant());
		request.complete(201, mapper.valueToTree(ReportUploadResponse.from(request.getParseAttempt())),
				clock.instant());
		return result(request);
	}

	public ReportIntakeResult fail(long requestId, ReportErrorCode code, String attemptError, Duration cleanupDelay) {
		var request = locked(requestId);
		if (request.getStatus() != ReportIntakeStatus.PROCESSING) {
			request.requireReplayable();
			return result(request);
		}
		return failLocked(request, code, attemptError, cleanupDelay);
	}

	/** 잠금 안에서 기한을 다시 확인해 중단된 접수를 실패로 마무리하고 원본의 지연 삭제를 예약한다. */
	public boolean recoverExpired(long requestId) {
		var request = locked(requestId);
		if (request.getStatus() != ReportIntakeStatus.PROCESSING
				|| clock.instant().isBefore(request.getProcessingDeadlineAt())) {
			return false;
		}
		failLocked(request, ReportErrorCode.INTAKE_PROCESSING_TIMEOUT,
				ReportErrorCode.INTAKE_PROCESSING_TIMEOUT.getCode(), properties.uncertainCleanupDelay());
		return true;
	}

	/** 잠근 접수의 실패 응답과 파싱 오류를 저장하고 원본 소유 기록에 삭제 예정 시각을 남긴다. */
	private ReportIntakeResult failLocked(
		ReportIntakeRequest request,
		ReportErrorCode code,
		String attemptError,
		Duration cleanupDelay
	) {
		var problem = mapper.createObjectNode()
				.put("type", "about:blank")
				.put("title", code.getHttpStatus().getReasonPhrase())
				.put("status", code.getHttpStatus().value())
				.put("detail", code.getMessage())
				.put("instance", "/api/v1/reports")
				.put("code", code.getCode());
		var attempt = request.getParseAttempt();
		if (attempt != null) {
			if (attempt.getProcessStatus() == ParseStatus.PROCESSING) {
				attempt.fail(attemptError, clock.instant());
			}
			problem.put("reportId", request.getReport().getId()).put("parseAttemptId", attempt.getId());
		}
		request.complete(code.getHttpStatus().value(), problem, clock.instant());
		var cleanupAfter = clock.instant().plus(cleanupDelay);
		if (request.getReport() != null) {
			request.getReport().scheduleSourceCleanup(cleanupAfter);
		} else {
			request.scheduleOrphanCleanup(cleanupAfter);
		}
		return result(request);
	}

	/** 지정한 시도가 접수의 현재 시도이며 아직 파싱을 처리 중인지 검사한다. */
	private void requireAttempt(ReportIntakeRequest request, ParseWork work) {
		requireAttemptIdentity(request, work);
		if (request.getParseAttempt().getProcessStatus() != ParseStatus.PROCESSING) {
			throw new ReportException(ReportErrorCode.INTAKE_REPORT_MISMATCH);
		}
	}

	/** 접수에 연결된 보고서와 현재 파싱 시도가 처리 대상 식별자와 일치하는지 검사한다. */
	private void requireAttemptIdentity(ReportIntakeRequest request, ParseWork work) {
		if (request.getParseAttempt() == null || request.getParseAttempt().getId() != work.attemptId()
				|| request.getReport().getId() != work.reportId()) {
			throw new ReportException(ReportErrorCode.INTAKE_REPORT_MISMATCH);
		}
	}

	private ReportIntakeRequest locked(long requestId) {
		return requests.findByIdForUpdate(requestId)
				.orElseThrow(
						() -> new ReportException(ReportErrorCode.INVALID_INTAKE_STATE));
	}

	public int purgeExpiredRequests(Instant now) {
		return requests.deleteExpiredBatch(now, intakeProperties.retentionBatchSize());
	}

	private ReportIntakeResult result(ReportIntakeRequest request) {
		return new ReportIntakeResult(request.getResponseStatus(), request.getResponseBody());
	}

	/** 선점한 접수의 식별자와 원본 키 또는 재사용할 완료 응답을 전달한다. */
	public record Reservation(
		long requestId,
		String objectKey,
		ReportIntakeResult replay
	) {
	}

	/** 외부 파싱 호출에 사용할 접수, 보고서와 파싱 시도의 식별자를 전달한다. */
	public record ParseWork(
		long requestId,
		long reportId,
		long attemptId,
		Instant deadline
	) {
	}
}
