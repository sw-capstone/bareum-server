package com.bareum.server.domain.report.parsing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** AI PDF 파싱 API를 호출하고 받은 응답이 요청 정보와 계약에 맞는지 확인한다. */
@RequiredArgsConstructor
public final class HttpPdfParser implements PdfParser {

	private final HttpClient client;
	private final URI endpoint;
	private final String token;
	private final Duration timeout;
	private final JsonMapper mapper = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.build();

	@Override
	public PdfParseResponse parse(PdfParseRequest request) {
		// TODO(#53): 접수 서비스에서 호출하고 동시 호출 제한과 새 시도 생성, 상태 저장을 연결한다.
		// 자동 재전송은 하지 않는다. 호출 시간 초과만으로 원격 파싱 종료를 단정할 수 없다.
		if (client.followRedirects() != HttpClient.Redirect.NEVER) {
			throw new IllegalArgumentException("PDF 파싱 클라이언트는 리다이렉트를 허용하지 않습니다.");
		}
		// 현재 PDF 파싱 호출은 HTTP/1.1을 유지한다.
		// 통신 병목이 확인되면 AI 서버와 프록시 설정을 함께 맞춰 HTTP/2 전환을 검토한다.
		HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
				.version(HttpClient.Version.HTTP_1_1)
				.timeout(timeout)
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request), StandardCharsets.UTF_8))
				.build();
		try {
			HttpResponse<String> response = client.send(httpRequest,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			String contentType = response.headers().firstValue("Content-Type").orElse("");
			if (!contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
				throw new ReportException(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT);
			}
			if (response.statusCode() == 200) {
				return success(request, mapper.readValue(response.body(), PdfParseResponse.Succeeded.class));
			}
			return failure(request, response);
		} catch (HttpTimeoutException exception) {
			throw new ReportException(ReportErrorCode.PDF_PARSE_REQUEST_TIMEOUT);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ReportException(ReportErrorCode.PDF_PARSE_REQUEST_INTERRUPTED);
		} catch (IOException exception) {
			throw new ReportException(ReportErrorCode.PDF_PARSE_CONNECTION_FAILED);
		} catch (JacksonException exception) {
			// 응답이나 다운로드 URL이 예외 원문을 통해 로그에 노출되지 않도록 한다.
			throw new ReportException(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT);
		}
	}

	private PdfParseResponse.Succeeded success(PdfParseRequest request, PdfParseResponse.Succeeded result) {
		if (result == null || !"SUCCEEDED".equals(result.status()) || result.startedAt() == null
				|| result.completedAt() == null || result.completedAt().isBefore(result.startedAt())
				|| result.extractionQuality() == null || result.parsed() == null || !result.parsed().isObject()
				|| (result.error() != null && !result.error().isNull())) {
			throw new ReportException(ReportErrorCode.INVALID_PARSE_RESPONSE_METADATA);
		}
		identity(request, result.reportId(), result.parseAttemptId());
		if (!"1.2.0".equals(result.parsed().path("schema_version").asString())) {
			throw new ReportException(ReportErrorCode.UNSUPPORTED_PARSE_SCHEMA_VERSION);
		}
		if (!request.source().sha256().equalsIgnoreCase(result.parsed().path("document").path("sha256").asString())) {
			throw new ReportException(ReportErrorCode.PARSE_SOURCE_MISMATCH);
		}
		return result;
	}

	private PdfParseResponse.Failed failure(PdfParseRequest request, HttpResponse<String> response) {
		PdfParseResponse.Failed result = mapper.readValue(response.body(), PdfParseResponse.Failed.class);
		if (result == null || result.error() == null || result.error().code() == null
				|| result.error().message() == null || result.error().message().isBlank()
				|| result.error().code().getHttpStatus() != response.statusCode()
				|| result.error().code().getAction() != result.error().action()
				|| (result.reportId() == null) != (result.parseAttemptId() == null)) {
			throw new ReportException(ReportErrorCode.INVALID_PARSE_ERROR_RESPONSE);
		}
		if (result.reportId() != null) {
			identity(request, result.reportId(), result.parseAttemptId());
		}
		Integer retryAfter = response.headers().firstValue("Retry-After").map(value -> {
			try {
				int seconds = Integer.parseInt(value);
				if (seconds < 0) {
					throw new NumberFormatException();
				}
				return seconds;
			} catch (NumberFormatException exception) {
				throw new ReportException(ReportErrorCode.INVALID_PARSE_ERROR_RESPONSE);
			}
		}).orElse(null);
		return new PdfParseResponse.Failed(result.reportId(), result.parseAttemptId(), result.error(), retryAfter);
	}

	private static void identity(PdfParseRequest request, long reportId, long parseAttemptId) {
		if (reportId != request.reportId()) {
			throw new ReportException(ReportErrorCode.PARSE_REPORT_MISMATCH);
		}
		if (parseAttemptId != request.parseAttemptId()) {
			throw new ReportException(ReportErrorCode.PARSE_ATTEMPT_MISMATCH);
		}
	}
}
