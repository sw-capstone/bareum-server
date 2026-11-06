package com.bareum.server.domain.report.parsing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import tools.jackson.databind.node.ObjectNode;

/** HTTP 호출과 응답 검증이 정상 응답, 오류 응답, 통신 실패를 올바르게 처리하는지 검사한다. */
class HttpPdfParserTests {

	private PdfApiFixture api;
	private HttpClient http;
	private HttpPdfParser parser;

	@BeforeEach
	void setUp() throws Exception {
		api = new PdfApiFixture();
		http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		parser = new HttpPdfParser(http, api.endpoint(), "example-internal-token", Duration.ofSeconds(5));
	}

	@AfterEach
	void tearDown() {
		api.close();
		http.close();
	}

	@Test
	void sendsCamelCaseRequestAndReadsExtractionEnvelope() throws Exception {
		var result = assertInstanceOf(PdfParseResponse.Succeeded.class, parser.parse(PdfApiFixture.request()));
		assertEquals("POST", api.method);
		assertEquals("Bearer example-internal-token", api.authorization);
		assertEquals(PdfApiFixture.request(), api.mapper.readValue(api.received, PdfParseRequest.class));
		assertTrue(api.mapper.readTree(api.received).has("reportId"));
		assertTrue(api.mapper.readTree(api.received).get("source").has("downloadUrl"));
		assertEquals(101, result.reportId());
		assertEquals(201, result.parseAttemptId());
		assertEquals(ExtractionQuality.PARTIAL, result.extractionQuality());
		assertEquals(api.success().get("parsed"), result.parsed());
	}

	@ParameterizedTest
	@CsvSource({
		"MALFORMED_JSON,400,FIX_REQUEST", "INVALID_REQUEST,422,FIX_REQUEST", "UNAUTHORIZED,401,STOP",
		"ATTEMPT_IN_PROGRESS,409,CREATE_NEW_ATTEMPT", "SOURCE_URL_EXPIRED,424,CREATE_NEW_ATTEMPT",
		"SOURCE_DOWNLOAD_TEMPORARY_FAILURE,424,CREATE_NEW_ATTEMPT", "SOURCE_ACCESS_DENIED,424,STOP",
		"SOURCE_NOT_FOUND,424,STOP", "SOURCE_IDENTITY_MISMATCH,422,STOP", "FILE_TOO_LARGE,413,STOP",
		"UNSUPPORTED_MEDIA_TYPE,415,STOP", "PAGE_LIMIT_EXCEEDED,422,STOP", "PDF_ENCRYPTED,422,STOP",
		"PDF_CORRUPTED,422,STOP", "NO_EXTRACTABLE_TEXT,422,STOP", "CAPACITY_EXCEEDED,503,CREATE_NEW_ATTEMPT",
		"PARSER_TIMEOUT,504,CREATE_NEW_ATTEMPT", "PARSER_FAILED,500,STOP", "CONFIGURATION_UNAVAILABLE,503,STOP"
	})
	void preservesRemoteFailureWithoutRetrying(String code, int status, String action) {
		api.status = status;
		api.body = api.failure(code, action).toString();
		api.retryAfter = "3";
		var result = assertInstanceOf(PdfParseResponse.Failed.class, parser.parse(PdfApiFixture.request()));
		assertEquals(code, result.error().code().name());
		assertEquals(action, result.error().action().name());
		assertEquals(3, result.retryAfterSeconds());
		assertEquals(1, api.calls.get());
	}

	@Test
	void allowsNullIdentityForFailureBeforeAcceptance() {
		api.status = 401;
		api.body = api.failure("UNAUTHORIZED", "STOP").putNull("reportId").putNull("parseAttemptId").toString();
		var result = assertInstanceOf(PdfParseResponse.Failed.class, parser.parse(PdfApiFixture.request()));
		assertNull(result.reportId());
		assertNull(result.parseAttemptId());
	}

	@ParameterizedTest
	@CsvSource({"reportId,PARSE_REPORT_MISMATCH", "parseAttemptId,PARSE_ATTEMPT_MISMATCH"})
	void rejectsSuccessForAnotherRequest(String field, ReportErrorCode expected) throws Exception {
		api.body = api.success().put(field, 999).toString();
		rejects(expected);
	}

	@Test
	void rejectsFailureForAnotherAttempt() {
		api.status = 504;
		api.body = api.failure("PARSER_TIMEOUT", "CREATE_NEW_ATTEMPT").put("parseAttemptId", 999).toString();
		rejects(ReportErrorCode.PARSE_ATTEMPT_MISMATCH);
	}

	@ParameterizedTest
	@ValueSource(strings = {"reportId", "parseAttemptId", "status", "startedAt", "completedAt", "extractionQuality",
		"parsed", "error"})
	void rejectsMissingSuccessFields(String field) throws Exception {
		ObjectNode result = api.success();
		result.remove(field);
		api.body = result.toString();
		rejects(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT);
	}

	@ParameterizedTest
	@CsvSource({"reportId,\"101\"", "parseAttemptId,201.5", "startedAt,\"invalid\"", "extractionQuality,\"UNKNOWN\""})
	void rejectsWrongSuccessTypes(String field, String json) throws Exception {
		ObjectNode result = api.success();
		result.set(field, api.mapper.readTree(json));
		api.body = result.toString();
		rejects(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT);
	}

	@Test
	void rejectsInvalidSuccessMetadata() throws Exception {
		api.body = api.success().put("completedAt", "2026-10-18T11:59:59Z").toString();
		rejects(ReportErrorCode.INVALID_PARSE_RESPONSE_METADATA);
	}

	@Test
	void rejectsUnsupportedExtractionVersion() throws Exception {
		ObjectNode result = api.success();
		((ObjectNode)result.get("parsed")).put("schema_version", "9.0.0");
		api.body = result.toString();
		rejects(ReportErrorCode.UNSUPPORTED_PARSE_SCHEMA_VERSION);
	}

	@Test
	void rejectsDifferentSourceHash() throws Exception {
		ObjectNode result = api.success();
		((ObjectNode)result.get("parsed").get("document")).put("sha256", "b".repeat(64));
		api.body = result.toString();
		rejects(ReportErrorCode.PARSE_SOURCE_MISMATCH);
	}

	@ParameterizedTest
	@CsvSource({"503,STOP", "500,CREATE_NEW_ATTEMPT"})
	void rejectsInconsistentErrorContract(int status, String action) {
		api.status = status;
		api.body = api.failure("CAPACITY_EXCEEDED", action).toString();
		rejects(ReportErrorCode.INVALID_PARSE_ERROR_RESPONSE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"-1", "invalid"})
	void rejectsInvalidRetryAfter(String value) {
		api.status = 503;
		api.body = api.failure("CAPACITY_EXCEEDED", "CREATE_NEW_ATTEMPT").toString();
		api.retryAfter = value;
		rejects(ReportErrorCode.INVALID_PARSE_ERROR_RESPONSE);
	}

	@Test
	void rejectsNonJsonGatewayResponse() {
		api.status = 502;
		api.contentType = "text/html";
		api.body = "<html>Bad Gateway</html>";
		rejects(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT);
	}

	@Test
	void malformedResponseDoesNotExposePayloadInException() {
		api.body = "{\"downloadUrl\":\"https://example.test/?signature=example-secret\",";
		ReportException error = rejects(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT);
		assertNull(error.getCause());
		assertFalse(error.toString().contains("example-secret"));
		assertFalse(PdfApiFixture.request().toString().contains("signature="));
	}

	@Test
	void timesOutWithoutAutomaticRetry() {
		api.hold = true;
		parser = new HttpPdfParser(http, api.endpoint(), "example-internal-token", Duration.ofMillis(200));
		rejects(ReportErrorCode.PDF_PARSE_REQUEST_TIMEOUT);
		assertTrue(api.calls.get() <= 1);
	}

	/** HTTP 호출이 기본 대기시간보다 짧은 접수의 남은 기한을 적용하는지 검증한다. */
	@Test
	void usesTheRemainingIntakeBudgetInsteadOfResettingTheConfiguredTimeout() {
		api.hold = true;
		var before = System.nanoTime();
		var error = assertThrows(ReportException.class,
				() -> parser.parse(PdfApiFixture.request(), Duration.ofMillis(200)));
		assertEquals(ReportErrorCode.PDF_PARSE_REQUEST_TIMEOUT, error.getBaseErrorCode());
		assertTrue(Duration.ofNanos(System.nanoTime() - before).compareTo(Duration.ofSeconds(2)) < 0);
		assertTrue(api.calls.get() <= 1);
	}

	/** 헤더만 받거나 본문 일부를 받은 뒤 전송이 멈춰도 남은 접수 기한 안에서 종료하는지 검증한다. */
	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void timesOutWhileReadingTheResponseBody(int prefixBytes) throws Exception {
		api.holdBody = true;
		api.bodyPrefixBytes = prefixBytes;
		var running = CompletableFuture.supplyAsync(() -> assertThrows(ReportException.class,
				() -> parser.parse(PdfApiFixture.request(), Duration.ofMillis(500))));
		try {
			assertTrue(api.headersSent.await(3, TimeUnit.SECONDS));
			var failure = running.get(2, TimeUnit.SECONDS);
			assertEquals(ReportErrorCode.PDF_PARSE_REQUEST_TIMEOUT, failure.getBaseErrorCode());
			assertEquals(1, api.calls.get());
		} finally {
			api.release.countDown();
		}
	}

	/** 대기시간 초과가 Future만 끝내는 대신 원래 HTTP 요청에도 취소를 전달하는지 검증한다. */
	@Test
	void cancelsTheOriginalExchangeWhenTheBodyDeadlineExpires() {
		var pending = new CompletableFuture<HttpResponse<String>>();
		var parser = parserWithPendingResponse(pending);
		var failure = assertThrows(ReportException.class,
				() -> parser.parse(PdfApiFixture.request(), Duration.ofMillis(50)));
		assertEquals(ReportErrorCode.PDF_PARSE_REQUEST_TIMEOUT, failure.getBaseErrorCode());
		assertTrue(pending.isCancelled());
	}

	/** 비동기 HTTP 실패의 원인을 풀어 기존 시간 초과와 연결 실패 오류를 유지하는지 검증한다. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void preservesTransportErrorsFromAsyncResponses(boolean timedOut) {
		var pending = new CompletableFuture<HttpResponse<String>>();
		pending.completeExceptionally(timedOut ? new HttpTimeoutException("fixture") : new IOException("fixture"));
		var parser = parserWithPendingResponse(pending);
		var failure = assertThrows(ReportException.class, () -> parser.parse(PdfApiFixture.request()));
		assertEquals(timedOut ? ReportErrorCode.PDF_PARSE_REQUEST_TIMEOUT : ReportErrorCode.PDF_PARSE_CONNECTION_FAILED,
				failure.getBaseErrorCode());
		assertNull(failure.getCause());
	}

	/** HTTP Future의 취소와 예외 전달을 실제 어댑터에서 검사할 클라이언트를 준비한다. */
	private HttpPdfParser parserWithPendingResponse(CompletableFuture<HttpResponse<String>> pending) {
		var client = mock(HttpClient.class);
		when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
		when(client.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
				.thenReturn(pending);
		return new HttpPdfParser(client, api.endpoint(), "example-internal-token", Duration.ofSeconds(5));
	}

	/** 접수 기한이 이미 지났으면 AI로 HTTP 요청을 보내지 않는지 검증한다. */
	@Test
	void expiredBudgetDoesNotSendAnHttpRequest() {
		var error = assertThrows(ReportException.class,
				() -> parser.parse(PdfApiFixture.request(), Duration.ZERO));
		assertEquals(ReportErrorCode.INTAKE_PROCESSING_TIMEOUT, error.getBaseErrorCode());
		assertEquals(0, api.calls.get());
	}

	/** 헤더나 본문을 기다리는 도중 인터럽트되어도 호출자의 인터럽트 상태를 복원하는지 검증한다. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void restoresInterruptedFlag(boolean bodyStarted) throws Exception {
		api.hold = !bodyStarted;
		api.holdBody = bodyStarted;
		CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
		Thread caller = Thread.ofVirtual().start(() -> {
			try {
				rejects(ReportErrorCode.PDF_PARSE_REQUEST_INTERRUPTED);
				interrupted.complete(Thread.currentThread().isInterrupted());
			} catch (AssertionError error) {
				interrupted.completeExceptionally(error);
			}
		});
		assertTrue((bodyStarted ? api.headersSent : api.entered).await(3, TimeUnit.SECONDS));
		caller.interrupt();
		assertTrue(interrupted.get(3, TimeUnit.SECONDS));
	}

	private ReportException rejects(ReportErrorCode code) {
		ReportException error = assertThrows(ReportException.class, () -> parser.parse(PdfApiFixture.request()));
		assertSame(code, error.getBaseErrorCode());
		return error;
	}
}
