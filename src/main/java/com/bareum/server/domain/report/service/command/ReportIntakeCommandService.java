package com.bareum.server.domain.report.service.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.domain.report.body.ValidatedReportBody;
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
	private final JsonMapper mapper = JsonMapper.builder().build();

	public ReportIntakeCommandService(
		MemberRepository members,
		ReportIntakeRequestRepository requests,
		ReportRepository reports,
		ReportParseAttemptRepository attempts,
		ReportContentRepository contents,
		@Qualifier("reportIntakeClock") Clock clock
	) {
		this.members = members;
		this.requests = requests;
		this.reports = reports;
		this.attempts = attempts;
		this.contents = contents;
		this.clock = clock;
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
		var request = ReportIntakeRequest.reserve(member, key, fingerprint, clock.instant());
		request.assignSourceKey(objectKey);
		requests.save(request);
		return new Reservation(request.getId(), objectKey, null);
	}

	public void recordSource(long requestId, UploadedReportFile source) {
		locked(requestId).recordUploadedSource(source);
	}

	public ParseWork startParsing(long requestId, ReportType type, UploadedReportFile source) {
		var request = locked(requestId);
		var report = reports.save(Report.createFromFile(request.getMember(), type, source));
		var attempt = attempts.save(ReportParseAttempt.request(report));
		attempt.start(clock.instant());
		request.attachAttempt(attempt);
		return new ParseWork(requestId, report.getId(), attempt.getId());
	}

	public ReportIntakeResult complete(ParseWork work, ValidatedReportBody body, ExtractionQuality quality) {
		var request = locked(work.requestId());
		if (request.getParseAttempt() == null || request.getParseAttempt().getId() != work.attemptId()
				|| request.getReport().getId() != work.reportId()) {
			throw new ReportException(ReportErrorCode.INTAKE_REPORT_MISMATCH);
		}
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

	private ReportIntakeRequest locked(long requestId) {
		return requests.findByIdForUpdate(requestId)
				.orElseThrow(
						() -> new ReportException(ReportErrorCode.INVALID_INTAKE_STATE));
	}

	public int purgeExpiredRequests(Instant now) {
		return requests.deleteExpiredBatch(now, 1000);
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
		long attemptId
	) {
	}
}
