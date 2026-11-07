package com.bareum.server.domain.report.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.bareum.server.domain.report.body.pdf.PdfBodyConverter;
import com.bareum.server.domain.report.config.ReportUploadProperties;
import com.bareum.server.domain.report.enums.PdfParseErrorCode;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.PdfUploadValidator;
import com.bareum.server.domain.report.intake.PreparedPdf;
import com.bareum.server.domain.report.intake.ReportIntakeLimiter;
import com.bareum.server.domain.report.intake.ReportIntakePolicy;
import com.bareum.server.domain.report.intake.ReportIntakeResult;
import com.bareum.server.domain.report.parsing.PdfParseResponse;
import com.bareum.server.domain.report.service.command.ReportIntakeCommandService;
import com.bareum.server.domain.report.service.query.ReportIntakeQueryService;
import com.bareum.server.domain.report.source.ReportSourceStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** DB 트랜잭션 밖에서 원본 업로드와 파싱을 실행하고 조회와 저장 서비스를 연결한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportUploadService {
	private final PdfUploadValidator validator;
	private final ReportSourceStore sources;
	private final ReportParsingService parsing;
	private final PdfBodyConverter converter;
	private final ReportIntakeQueryService queries;
	private final ReportIntakeCommandService commands;
	private final ReportUploadProperties properties;
	private final ReportIntakeLimiter limiter;

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ReportIntakeResult upload(long memberId, UUID key, ReportType type, MultipartFile file) {
		var pdf = validator.prepare(file);
		var fingerprint = validator.fingerprint(type, pdf);
		var replay = queries.findReplay(memberId, key, fingerprint);
		if (replay.isPresent()) {
			return replay.get();
		}
		if (!limiter.tryAcquire()) {
			// 자리 확인 직전에 완료되거나 선점된 동일 요청은 기존 멱등 정책을 우선한다.
			return queries.findReplay(memberId, key, fingerprint)
					.orElseThrow(() -> new ReportException(ReportErrorCode.PDF_CAPACITY_EXCEEDED));
		}
		try {
			return accept(memberId, key, type, pdf, fingerprint);
		} finally {
			limiter.release();
		}
	}

	/** 확보한 처리 자리 안에서 접수를 선점하고 원본 저장부터 파싱 및 결과 저장까지 수행한다. */
	private ReportIntakeResult accept(long memberId, UUID key, ReportType type, PreparedPdf pdf, String fingerprint) {
		var reservation =
				commands.reserve(memberId, key, fingerprint, sources.newObjectKey(memberId));
		if (reservation.replay() != null) {
			return reservation.replay();
		}
		try {
			properties.requireConfigured();
			var source = sources.upload(reservation.objectKey(), pdf);
			commands.recordSource(reservation.requestId(), source);
			var work = commands.startParsing(reservation.requestId(), type, source);
			var parsed = parsing.parse(work, source);
			var response = parsed.response();
			if (response instanceof PdfParseResponse.Failed failed) {
				return commands.fail(reservation.requestId(), mapError(failed.error().code()),
						failed.error().code().name(),
						failed.error().code() == PdfParseErrorCode.ATTEMPT_IN_PROGRESS ? uncertainCleanupDelay()
								: Duration.ZERO);
			}
			var succeeded = (PdfParseResponse.Succeeded)response;
			var count = succeeded.parsed().path("document").path("page_count");
			if (!count.isIntegralNumber() || !count.canConvertToInt() || count.asInt() < 1) {
				throw new ReportException(ReportErrorCode.INVALID_PARSE_DOCUMENT_METADATA);
			}
			if (count.asInt() > ReportIntakePolicy.MAX_PAGE_COUNT) {
				throw new ReportException(ReportErrorCode.PAGE_LIMIT_EXCEEDED);
			}
			var body = converter.convertParsed(succeeded.parsed().toString());
			return commands.complete(parsed.work(), body, succeeded.extractionQuality());
		} catch (ReportException exception) {
			var code = (ReportErrorCode)exception.getBaseErrorCode();
			boolean uncertain = code == ReportErrorCode.PDF_PARSE_REQUEST_TIMEOUT
					|| code == ReportErrorCode.PDF_PARSE_CONNECTION_FAILED
					|| code == ReportErrorCode.PDF_PARSE_REQUEST_INTERRUPTED;
			uncertain |= code == ReportErrorCode.INTAKE_PROCESSING_TIMEOUT
					|| code == ReportErrorCode.SOURCE_STORAGE_FAILED
					|| code == ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT
					|| code == ReportErrorCode.INVALID_PARSE_ERROR_RESPONSE;
			return commands.fail(reservation.requestId(), code, code.getCode(),
					uncertain ? uncertainCleanupDelay() : Duration.ZERO);
		} catch (RuntimeException exception) {
			log.error("Report intake storage failed: requestId={}, failureType={}", reservation.requestId(),
					exception.getClass().getSimpleName());
			return commands.fail(reservation.requestId(), ReportErrorCode.INTAKE_PERSISTENCE_FAILED,
					ReportErrorCode.INTAKE_PERSISTENCE_FAILED.getCode(), uncertainCleanupDelay());
		}
	}

	private Duration uncertainCleanupDelay() {
		return properties.uncertainCleanupDelay();
	}

	private static ReportErrorCode mapError(PdfParseErrorCode code) {
		return switch (code) {
			case FILE_TOO_LARGE -> ReportErrorCode.FILE_TOO_LARGE;
			case UNSUPPORTED_MEDIA_TYPE -> ReportErrorCode.UNSUPPORTED_FILE_TYPE;
			case PAGE_LIMIT_EXCEEDED -> ReportErrorCode.PAGE_LIMIT_EXCEEDED;
			case PDF_ENCRYPTED -> ReportErrorCode.PDF_ENCRYPTED;
			case PDF_CORRUPTED -> ReportErrorCode.PDF_CORRUPTED;
			case NO_EXTRACTABLE_TEXT -> ReportErrorCode.NO_EXTRACTABLE_TEXT;
			case CAPACITY_EXCEEDED -> ReportErrorCode.PDF_CAPACITY_EXCEEDED;
			case PARSER_TIMEOUT -> ReportErrorCode.PDF_PARSE_REQUEST_TIMEOUT;
			case PARSER_FAILED -> ReportErrorCode.PDF_PARSER_FAILED;
			case SOURCE_URL_EXPIRED, SOURCE_DOWNLOAD_TEMPORARY_FAILURE, SOURCE_ACCESS_DENIED, SOURCE_NOT_FOUND ->
				ReportErrorCode.PDF_SOURCE_UNAVAILABLE;
			case MALFORMED_JSON, INVALID_REQUEST, UNAUTHORIZED, ATTEMPT_IN_PROGRESS, SOURCE_IDENTITY_MISMATCH,
					CONFIGURATION_UNAVAILABLE ->
				ReportErrorCode.PDF_PARSER_UNAVAILABLE;
		};
	}
}
