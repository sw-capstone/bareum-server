package com.bareum.server.domain.report.service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.report.enums.PdfParseErrorCode;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.ReportIntakePolicy;
import com.bareum.server.domain.report.parsing.PdfParseRequest;
import com.bareum.server.domain.report.parsing.PdfParseResponse;
import com.bareum.server.domain.report.parsing.PdfParser;
import com.bareum.server.domain.report.service.command.ReportIntakeCommandService;
import com.bareum.server.domain.report.service.command.ReportIntakeCommandService.ParseWork;
import com.bareum.server.domain.report.source.ReportSourceStore;
import com.bareum.server.domain.report.source.UploadedReportFile;

import lombok.RequiredArgsConstructor;

/** 접수 전체 기한 안에서 일시적 파싱 오류에만 새 시도로 추가 1회 재요청한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ReportParsingService {
	private final PdfParser parser;
	private final ReportSourceStore sources;
	private final ReportIntakeCommandService commands;
	private final Clock clock;

	/** 원본을 다시 올리지 않고 새 URL과 시도 이력으로 일시적 파싱 실패를 기한 안에서 1회 재시도한다. */
	public ParsedReport parse(ParseWork first, UploadedReportFile source) {
		var work = first;
		for (int additional = 0;; additional++) {
			commands.requireParsingActive(work);
			var download = sources.presign(source);
			var request = new PdfParseRequest(work.reportId(), work.attemptId(), new PdfParseRequest.Source(
					source.objectKey(), source.originalFilename(), source.sizeBytes(), source.sha256(),
					download.url(), download.expiresAt().atOffset(ZoneOffset.UTC)));
			var remaining = Duration.between(clock.instant(), work.deadline());
			if (remaining.isZero() || remaining.isNegative()) {
				throw new ReportException(ReportErrorCode.INTAKE_PROCESSING_TIMEOUT);
			}
			var response = parser.parse(request, remaining);
			if (!(response instanceof PdfParseResponse.Failed failed)
					|| additional >= ReportIntakePolicy.MAX_ADDITIONAL_PARSE_ATTEMPTS
					|| !retryable(failed.error().code())) {
				return new ParsedReport(work, response);
			}
			var delay = retryDelay(failed);
			if (!clock.instant().plus(delay).isBefore(work.deadline())) {
				return new ParsedReport(work, response);
			}
			commands.recordRetryableFailure(work, failed.error().code().name());
			try {
				Thread.sleep(delay);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new ReportException(ReportErrorCode.PDF_PARSE_REQUEST_INTERRUPTED);
			}
			work = commands.retryParsing(work, failed.error().code().name());
		}
	}

	/** URL 만료와 일시적인 다운로드 실패, 처리 용량 부족만 자동 재시도 대상으로 허용한다. */
	private boolean retryable(PdfParseErrorCode code) {
		return switch (code) {
			case SOURCE_URL_EXPIRED, SOURCE_DOWNLOAD_TEMPORARY_FAILURE, CAPACITY_EXCEEDED -> true;
			default -> false;
		};
	}

	/** 용량 부족은 서버의 재요청 대기시간을 따르고 나머지 일시적 오류는 유형별 대기시간을 정한다. */
	private Duration retryDelay(PdfParseResponse.Failed failed) {
		return switch (failed.error().code()) {
			case SOURCE_URL_EXPIRED -> Duration.ZERO;
			case SOURCE_DOWNLOAD_TEMPORARY_FAILURE -> Duration.ofSeconds(1);
			case CAPACITY_EXCEEDED ->
				Duration.ofSeconds(failed.retryAfterSeconds() == null ? 3 : failed.retryAfterSeconds());
			default -> throw new IllegalArgumentException("재시도할 수 없는 파싱 오류입니다.");
		};
	}

	/** 마지막 파싱 시도의 식별자와 해당 응답을 함께 전달한다. */
	public record ParsedReport(
		ParseWork work,
		PdfParseResponse response
	) {
	}
}
