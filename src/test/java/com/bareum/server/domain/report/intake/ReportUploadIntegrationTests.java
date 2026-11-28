package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bareum.server.domain.auth.service.EmailLoginCredentialService.ValidatedMember;
import com.bareum.server.domain.auth.service.LoginSessionService;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.PdfParseErrorCode;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.repository.ReportContentRepository;
import com.bareum.server.domain.report.service.ReportUploadService;
import com.bareum.server.domain.report.source.ReportSourceStore;
import com.bareum.server.domain.report.source.ReportSourceStore.DownloadSource;
import com.bareum.server.domain.report.source.UploadedReportFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP 요청부터 파싱 호출과 본문 저장, 실패 기록까지 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"auth.login.allowed-origins=https://web.example.invalid",
		"report.upload.bucket=fixture-bucket", "report.upload.parser-token=fixture-token",
		"report.upload.parser-timeout-seconds=2"
	})
class ReportUploadIntegrationTests {
	private static final UploadParserFixture API = new UploadParserFixture();
	private static final byte[] PDF = "%PDF-1.7\nintake-fixture".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] ZIP_HEADER = {0x50, 0x4b, 0x03, 0x04};
	private final HttpClient client = HttpClient.newHttpClient();
	private final JsonMapper mapper = JsonMapper.builder().build();
	@LocalServerPort
	private int port;
	@Autowired
	private MemberRepository members;
	@Autowired
	private LoginSessionService sessions;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ReportUploadService uploads;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@MockitoBean
	private ReportSourceStore sources;
	@MockitoSpyBean
	private ReportContentRepository contents;
	@MockitoSpyBean
	private ReportPdfConverter reportPdfConverter;
	private Member member;
	private String token;

	@DynamicPropertySource
	static void parserEndpoint(DynamicPropertyRegistry registry) {
		registry.add("report.upload.parser-endpoint", API::endpoint);
	}

	@BeforeEach
	void setUp() {
		API.reset();
		member = members.saveAndFlush(
				Member.createLocal("upload-" + UUID.randomUUID() + "@example.invalid", "tester", "test-hash"));
		token = sessions.issue(new ValidatedMember(member.getId(), member.getPasswordHash())).accessToken();
		when(sources.newObjectKey(anyLong(), any(FileFormat.class)))
				.thenAnswer(i -> "reports/" + i.getArgument(0) + "/" + UUID.randomUUID()
						+ i.<FileFormat>getArgument(1).extension());
		when(sources.upload(anyString(), any())).thenAnswer(i -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			PreparedReportFile file = i.getArgument(1);
			return new UploadedReportFile(file.format(), i.getArgument(0), file.filename(), file.sizeBytes(),
					file.sha256(), "version-fixture", Instant.now());
		});
		when(sources.presign(any())).thenAnswer(i -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			UploadedReportFile file = i.getArgument(0);
			return new ReportSourceStore.DownloadSource(
					URI.create("https://fixture-bucket.s3.ap-northeast-2.amazonaws.com/" + file.objectKey()
							+ "?signature=fixture"),
					Instant.now().plusSeconds(600));
		});
	}

	@AfterEach
	void cleanUp() {
		API.release.countDown();
		long id = member.getId();
		jdbc.update("DELETE FROM report_intake_request WHERE member_id = ?", id);
		jdbc.update("DELETE FROM report_parse_attempt WHERE report_id IN (SELECT id FROM report WHERE member_id = ?)",
				id);
		jdbc.update("DELETE FROM report_content WHERE report_id IN (SELECT id FROM report WHERE member_id = ?)", id);
		jdbc.update("DELETE FROM report_file WHERE report_id IN (SELECT id FROM report WHERE member_id = ?)", id);
		jdbc.update("DELETE FROM report WHERE member_id = ?", id);
		jdbc.update("DELETE FROM refresh_token WHERE member_id = ?", id);
		jdbc.update("DELETE FROM member WHERE id = ?", id);
		client.close();
	}

	@AfterAll
	static void stopParser() {
		API.close();
	}

	@Test
	void storesSourceParsedBodyAndHistoryBeforeReplyAndReplaysTheSameIds() throws Exception {
		UUID key = UUID.randomUUID();
		var first = upload(key, "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(201, first.statusCode(), first.body());
		JsonNode result = json(first);
		long report = result.get("reportId").asLong();
		long attempt = result.get("parseAttemptId").asLong();
		long content = result.get("contentId").asLong();
		assertEquals("Bearer fixture-token", API.authorization);
		assertEquals(report, API.received.get("reportId").asLong());
		assertEquals(attempt, API.received.get("parseAttemptId").asLong());
		assertEquals("PLAN", jdbc.queryForObject("SELECT report_type FROM report WHERE id=?", String.class, report));
		assertEquals("version-fixture",
				jdbc.queryForObject("SELECT version_id FROM report_file WHERE report_id=?", String.class, report));
		assertEquals("report.pdf",
				jdbc.queryForObject("SELECT filename FROM report_file WHERE report_id=?", String.class, report));
		assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT process_status FROM report_parse_attempt WHERE id=?",
				String.class, attempt));
		assertEquals(content, jdbc.queryForObject("SELECT produced_content_id FROM report_parse_attempt WHERE id=?",
				Long.class, attempt));
		assertEquals(report,
				jdbc.queryForObject("SELECT report_id FROM report_content WHERE id=?", Long.class, content));
		assertFalse(result.has("sourceExpiresAt"));
		assertEquals(0,
				jdbc.queryForObject("SELECT count(*) FROM report_analysis WHERE content_id=?", Integer.class, content));
		var repeated = upload(key, "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(201, repeated.statusCode());
		assertEquals(result, json(repeated));
		assertEquals(1, API.calls.get());
		verify(sources, times(1)).upload(anyString(), any());
		verifyNoInteractions(reportPdfConverter);
		var changed = upload(key, "RESULT", "report.pdf", PDF, 1, token);
		assertEquals(409, changed.statusCode());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void intakeAndFailureRecordsSurviveAnUnrelatedCallerTransactionRollback(boolean failed) {
		UUID key = UUID.randomUUID();
		if (failed)
			API.error = PdfParseErrorCode.PARSER_FAILED;
		var result = new TransactionTemplate(transactionManager).execute(status -> {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			var response = uploads.upload(member.getId(), key, ReportType.PLAN,
					new MockMultipartFile("file", "report.pdf", "application/pdf", PDF));
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			status.setRollbackOnly();
			return response;
		});
		assertNotNull(result);
		assertEquals(failed ? 500 : 201, result.status(), result.body().toString());
		assertEquals(failed ? "FAILED" : "SUCCEEDED", jdbc.queryForObject(
				"SELECT status FROM report_intake_request WHERE member_id=? AND request_key=?",
				String.class, member.getId(), key));
		assertEquals(failed ? "FAILED" : "SUCCEEDED", jdbc.queryForObject(
				"SELECT process_status FROM report_parse_attempt WHERE id=?", String.class,
				result.body().get("parseAttemptId").asLong()));
	}

	@Test
	void returnsFreshPreviewUrlsToTheOwnerBeforeAnalysis() throws Exception {
		HttpResponse<String> uploaded = upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(201, uploaded.statusCode(), uploaded.body());
		long reportId = json(uploaded).get("reportId").asLong();
		String originalKey = jdbc.queryForObject("SELECT object_key FROM report_file WHERE report_id=?",
				String.class, reportId);
		Instant expiresAt = Instant.now().plusSeconds(600);
		URI firstUrl = URI.create("https://files.example.invalid/preview.pdf?signature=first");
		URI secondUrl = URI.create("https://files.example.invalid/preview.pdf?signature=second");
		when(sources.presignPreview(originalKey, "version-fixture"))
				.thenReturn(new DownloadSource(firstUrl, expiresAt),
						new DownloadSource(secondUrl, expiresAt.plusSeconds(1)));

		HttpResponse<String> first = preview(reportId, token);
		assertEquals(200, first.statusCode(), first.body());
		assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
		JsonNode result = json(first);
		assertEquals(reportId, result.get("reportId").asLong());
		assertEquals(firstUrl.toString(), result.get("pdfUrl").asString());
		assertEquals(expiresAt, Instant.parse(result.get("expiresAt").asString()));
		assertEquals(secondUrl.toString(), json(preview(reportId, token)).get("pdfUrl").asString());
		assertEquals(1, API.calls.get());
		verify(sources, times(2)).presignPreview(originalKey, "version-fixture");
	}

	@Test
	void blocksUnauthenticatedAndOtherMembersWithoutSigning() throws Exception {
		Long reportId =
				jdbc.queryForObject("INSERT INTO report(member_id, report_type) VALUES (?, 'PLAN') RETURNING id",
						Long.class, member.getId());
		Member other = members.saveAndFlush(
				Member.createLocal("preview-" + UUID.randomUUID() + "@example.invalid", "tester", "test-hash"));
		try {
			String otherToken =
					sessions.issue(new ValidatedMember(other.getId(), other.getPasswordHash())).accessToken();
			assertEquals(401, preview(reportId, null).statusCode());
			HttpResponse<String> denied = preview(reportId, otherToken);
			assertEquals(404, denied.statusCode(), denied.body());
			assertEquals("REPORT-0038", json(denied).get("code").asString());
			HttpResponse<String> notReady = preview(reportId, token);
			assertEquals(409, notReady.statusCode(), notReady.body());
			assertEquals("REPORT-0058", json(notReady).get("code").asString());
			assertEquals(400, preview(0, token).statusCode());
			verifyNoInteractions(sources);
		} finally {
			jdbc.update("DELETE FROM refresh_token WHERE member_id=?", other.getId());
			jdbc.update("DELETE FROM member WHERE id=?", other.getId());
		}
	}

	@ParameterizedTest
	@EnumSource(value = FileFormat.class, names = {"DOCX", "HWPX"})
	void parsesAndPreviewsTheSameConvertedPdfForAnOfficeDocument(FileFormat format) throws Exception {
		UUID key = UUID.randomUUID();
		String filename = "report" + format.extension();
		PreparedReportFile pdf = new PreparedReportFile(FileFormat.PDF, PDF, "report.pdf");
		doAnswer(invocation -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			PreparedReportFile original = invocation.getArgument(0);
			assertEquals(format, original.format());
			assertArrayEquals(ZIP_HEADER, original.openStream().readAllBytes());
			return pdf;
		}).when(reportPdfConverter).convert(any(), any());

		HttpResponse<String> uploaded = upload(key, "PLAN", filename, ZIP_HEADER, 1, token, format.getMediaType());
		assertEquals(201, uploaded.statusCode(), uploaded.body());
		long reportId = json(uploaded).get("reportId").asLong();
		String originalKey = jdbc.queryForObject(
				"SELECT object_key FROM report_file WHERE report_id=? AND file_role='ORIGINAL' AND filename=?",
				String.class, reportId, filename);
		String convertedKey = jdbc.queryForObject(
				"SELECT object_key FROM report_file WHERE report_id=? AND file_role='CONVERTED_PDF' AND sha256=?",
				String.class, reportId, pdf.sha256());
		assertNotEquals(originalKey, convertedKey);
		assertTrue(originalKey.endsWith(format.extension()));
		assertTrue(convertedKey.endsWith(".pdf"));
		assertEquals(convertedKey, API.received.get("source").get("objectKey").asString());
		verify(sources).presign(
				argThat(file -> file.objectKey().equals(convertedKey) && "version-fixture".equals(file.versionId())));

		URI convertedUrl = URI.create("https://files.example.invalid/converted.pdf?signature=fixture");
		when(sources.presignPreview(convertedKey, "version-fixture"))
				.thenReturn(new DownloadSource(convertedUrl, Instant.now().plusSeconds(600)));
		HttpResponse<String> converted = preview(reportId, token);
		assertEquals(200, converted.statusCode(), converted.body());
		assertEquals(convertedUrl.toString(), json(converted).get("pdfUrl").asString());
		HttpResponse<String> repeated = upload(key, "PLAN", filename, ZIP_HEADER, 1, token, format.getMediaType());
		assertEquals(201, repeated.statusCode(), repeated.body());
		assertEquals(json(uploaded), json(repeated));
		assertEquals(1, API.calls.get());
		verify(sources, times(2)).upload(anyString(), any());
		verify(reportPdfConverter).convert(any(), any());

		jdbc.update("UPDATE report_file SET deleted_at=now(), cleanup_after=now() WHERE object_key=?", convertedKey);
		HttpResponse<String> removed = preview(reportId, token);
		assertEquals(404, removed.statusCode(), removed.body());
		assertEquals("REPORT-0059", json(removed).get("code").asString());
		verify(sources).presignPreview(convertedKey, "version-fixture");
	}

	@Test
	void malformedOfficePackageFailsBeforeParsingAndSchedulesOnlyTheOriginalForCleanup() throws Exception {
		UUID key = UUID.randomUUID();
		HttpResponse<String> result = upload(key, "PLAN", "report.docx", ZIP_HEADER, 1, token,
				FileFormat.DOCX.getMediaType());
		assertEquals(422, result.statusCode(), result.body());
		assertEquals("REPORT-0061", json(result).get("code").asString());
		assertFalse(json(result).has("parseAttemptId"));
		long reportId = json(result).get("reportId").asLong();
		assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM report_intake_request WHERE report_id=?",
				String.class, reportId));
		assertEquals(1, jdbc.queryForObject("""
				SELECT count(*) FROM report_file
				WHERE report_id=? AND file_role='ORIGINAL' AND cleanup_after IS NOT NULL AND deleted_at IS NOT NULL
				""", Integer.class, reportId));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM report_content WHERE report_id=?",
				Integer.class, reportId));
		assertEquals(404, preview(reportId, token).statusCode());
		assertEquals(0, API.calls.get());
		verify(sources).upload(anyString(), any());
	}

	@Test
	void acceptsSixtyPagesAndPartialExtractionWithWarnings() throws Exception {
		API.pageCount = 60;
		API.quality = "PARTIAL";
		var response = upload(UUID.randomUUID(), "RESULT", "report.pdf", PDF, 1, token);
		assertEquals(201, response.statusCode(), response.body());
		assertEquals("PARTIAL", json(response).get("extractionQuality").asString());
		assertFalse(json(response).get("warnings").isEmpty());
		assertEquals(60,
				jdbc.queryForObject("SELECT jsonb_array_length(content->'pages') FROM report_content WHERE id=?",
						Integer.class, json(response).get("contentId").asLong()));
	}

	@Test
	void rejectsSixtyOnePagesEvenWhenTheRemoteClaimsSuccess() throws Exception {
		API.pageCount = 61;
		var response = upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(422, response.statusCode(), response.body());
		assertEquals("REPORT-0048", json(response).get("code").asString());
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM report_content WHERE report_id=?", Integer.class,
				json(response).get("reportId").asLong()));
	}

	@ParameterizedTest
	@CsvSource({"PDF_ENCRYPTED,422", "PDF_CORRUPTED,422", "NO_EXTRACTABLE_TEXT,422", "PARSER_FAILED,500",
		"UNAUTHORIZED,502"})
	void preservesFailedAttemptAndReturnsAServiceErrorInsteadOfInternalDiagnostics(PdfParseErrorCode code, int expected)
			throws Exception {
		API.error = code;
		UUID key = UUID.randomUUID();
		var response = upload(key, "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(expected, response.statusCode(), response.body());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"));
		assertFalse(response.body().contains("private-parser-diagnostic"));
		assertEquals(code.name(), jdbc.queryForObject("SELECT error_code FROM report_parse_attempt WHERE id=?",
				String.class, json(response).get("parseAttemptId").asLong()));
		assertNotNull(jdbc.queryForObject("SELECT cleanup_after FROM report_file WHERE report_id=?",
				java.sql.Timestamp.class, json(response).get("reportId").asLong()));
		assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM report WHERE id=?",
				java.sql.Timestamp.class, json(response).get("reportId").asLong()));
		assertEquals(json(response), json(upload(key, "PLAN", "report.pdf", PDF, 1, token)));
		assertEquals(1, API.calls.get());
	}

	@Test
	void rejectsMismatchedParserHashAndKeepsTheFailedHistory() throws Exception {
		API.wrongHash = true;
		var response = upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(500, response.statusCode(), response.body());
		assertEquals("REPORT-0029", json(response).get("code").asString());
	}

	@Test
	void authenticatesAndValidatesPartsAndFieldsBeforeTouchingStorage() throws Exception {
		assertEquals(401, upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 1, null).statusCode());
		assertEquals(400, upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 2, token).statusCode());
		assertEquals(400, upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 0, token).statusCode());
		assertEquals(400, upload(UUID.randomUUID(), "UNKNOWN", "report.pdf", PDF, 1, token).statusCode());
		assertEquals(415,
				upload(UUID.randomUUID(), "PLAN", "report.pdf", "not-pdf".getBytes(StandardCharsets.US_ASCII), 1, token)
						.statusCode());
		assertEquals(400, upload(null, "PLAN", "report.pdf", PDF, 1, token).statusCode());
		assertEquals(0, API.calls.get());
		verify(sources, never()).upload(anyString(), any());
	}

	@Test
	void browserPreflightAllowsTheRequiredIdempotencyHeader() throws Exception {
		var response = send(HttpRequest.newBuilder(uri("/api/v1/reports"))
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.header("Origin", "https://web.example.invalid")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"));
		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.headers()
				.firstValue("Access-Control-Allow-Headers")
				.orElse("")
				.toLowerCase()
				.contains("idempotency-key"));
	}

	@Test
	void servletRejectsAnOversizedFileWithThePublicProblemFormat() throws Exception {
		byte[] oversized = java.util.Arrays.copyOf(PDF, 20_000_001);
		var response = upload(UUID.randomUUID(), "PLAN", "report.pdf", oversized, 1, token);
		assertEquals(413, response.statusCode(), response.body());
		assertEquals("COMMON-0008", json(response).get("code").asString());
		assertEquals(0, API.calls.get());
		verify(sources, never()).upload(anyString(), any());
	}

	@Test
	void s3FailureRetainsThePlannedKeyAndDoesNotCreateAReportOrParse() throws Exception {
		doThrow(new ReportException(ReportErrorCode.SOURCE_STORAGE_FAILED)).when(sources).upload(anyString(), any());
		UUID key = UUID.randomUUID();
		var response = upload(key, "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(502, response.statusCode(), response.body());
		assertFalse(json(response).has("reportId"));
		assertNotNull(jdbc.queryForObject(
				"SELECT source_cleanup_after FROM report_intake_request WHERE member_id=? AND request_key=?",
				java.sql.Timestamp.class, member.getId(), key));
		assertEquals(0, API.calls.get());
		assertEquals(0,
				jdbc.queryForObject("SELECT count(*) FROM report WHERE member_id=?", Integer.class, member.getId()));
		doAnswer(i -> {
			PreparedReportFile pdf = i.getArgument(1);
			return new UploadedReportFile(FileFormat.PDF, i.getArgument(0), pdf.filename(), pdf.sizeBytes(),
					pdf.sha256(), "version-fixture", Instant.now());
		}).when(sources).upload(anyString(), any());
		var next = upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(201, next.statusCode(), next.body());
	}

	@Test
	void finalStorageFailureRollsBackTheBodyAndRecordsTheFailureSeparately() throws Exception {
		doThrow(new org.springframework.dao.DataIntegrityViolationException("fixture persistence failure"))
				.when(contents)
				.save(any());
		var response = upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(500, response.statusCode(), response.body());
		assertEquals("REPORT-0055", json(response).get("code").asString());
		long report = json(response).get("reportId").asLong();
		assertEquals(0,
				jdbc.queryForObject("SELECT count(*) FROM report_content WHERE report_id=?", Integer.class, report));
		assertEquals("FAILED", jdbc.queryForObject("SELECT process_status FROM report_parse_attempt WHERE report_id=?",
				String.class, report));
		assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM report_intake_request WHERE report_id=?",
				String.class, report));
	}

	@Test
	void cancellationPersistsTheDeletionRequestAndPreventsSuccessfulResponseReplay() throws Exception {
		UUID key = UUID.randomUUID();
		var result = upload(key, "PLAN", "report.pdf", PDF, 1, token);
		long report = json(result).get("reportId").asLong();
		String path = "/api/v1/reports/" + report + "/source";
		var deleted = send(HttpRequest.newBuilder(uri(path)).DELETE().header("Authorization", "Bearer " + token));
		assertEquals(202, deleted.statusCode(), deleted.body());
		assertEquals("PENDING", json(deleted).get("sourceDeletionStatus").asString());
		assertEquals(202, send(HttpRequest.newBuilder(uri(path)).DELETE().header("Authorization", "Bearer " + token))
				.statusCode());
		assertEquals(404, preview(report, token).statusCode());
		assertEquals(409, upload(key, "PLAN", "report.pdf", PDF, 1, token).statusCode());
		assertEquals(1, API.calls.get());
		assertNotNull(jdbc.queryForObject("SELECT intake_cancelled_at FROM report WHERE id=?", java.sql.Timestamp.class,
				report));
		assertNull(
				jdbc.queryForObject("SELECT s3_deleted_at FROM report_file WHERE report_id=?", java.sql.Timestamp.class,
						report));
		assertEquals(1,
				jdbc.queryForObject("SELECT count(*) FROM report_content WHERE report_id=?", Integer.class, report));
	}

	@Test
	void simultaneousDuplicateDoesNotCallParserTwiceAndCannotDeleteAnActiveParse() throws Exception {
		API.hold = true;
		UUID key = UUID.randomUUID();
		var running = CompletableFuture.supplyAsync(() -> {
			try {
				return upload(key, "PLAN", "report.pdf", PDF, 1, token);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		try {
			assertTrue(API.entered.await(10, TimeUnit.SECONDS));
			assertEquals(409, upload(key, "PLAN", "report.pdf", PDF, 1, token).statusCode());
			HttpResponse<String> notReady = preview(API.received.get("reportId").asLong(), token);
			assertEquals(409, notReady.statusCode(), notReady.body());
			assertEquals("REPORT-0058", json(notReady).get("code").asString());
			var deletion = send(
					HttpRequest.newBuilder(uri("/api/v1/reports/" + API.received.get("reportId").asLong() + "/source"))
							.DELETE()
							.header("Authorization", "Bearer " + token));
			assertEquals(409, deletion.statusCode(), deletion.body());
		} finally {
			API.release.countDown();
		}
		assertEquals(201, running.get(10, TimeUnit.SECONDS).statusCode());
		assertEquals(1, API.calls.get());
	}

	/** 서로 다른 접수의 동시 실행을 S3 저장 전에 거절하고 거절된 키를 나중에 재사용하는지 검증한다. */
	@Test
	void rejectsConcurrentNewIntakesBeforeReservationAndStorageButAllowsTheSameKeyLater() throws Exception {
		UUID completedKey = UUID.randomUUID();
		var completed = upload(completedKey, "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(201, completed.statusCode(), completed.body());
		API.reset();
		API.hold = true;
		UUID runningKey = UUID.randomUUID();
		UUID rejectedKey = UUID.randomUUID();
		var running = CompletableFuture.supplyAsync(() -> {
			try {
				return upload(runningKey, "PLAN", "report.pdf", PDF, 1, token);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		try {
			assertTrue(API.entered.await(5, TimeUnit.SECONDS));
			var rejected = upload(rejectedKey, "PLAN", "report.pdf", PDF, 1, token);
			assertEquals(503, rejected.statusCode(), rejected.body());
			assertEquals("REPORT-0053", json(rejected).get("code").asString());
			assertEquals(0, jdbc.queryForObject(
					"SELECT count(*) FROM report_intake_request WHERE member_id=? AND request_key=?",
					Integer.class, member.getId(), rejectedKey));
			var replayed = upload(completedKey, "PLAN", "report.pdf", PDF, 1, token);
			assertEquals(201, replayed.statusCode(), replayed.body());
			assertEquals(json(completed), json(replayed));
			assertEquals(409, upload(runningKey, "PLAN", "report.pdf", PDF, 1, token).statusCode());
			assertEquals(409, upload(completedKey, "RESULT", "report.pdf", PDF, 1, token).statusCode());
			assertEquals(1, API.calls.get());
			verify(sources, times(2)).upload(anyString(), any());
		} finally {
			API.release.countDown();
			running.get(5, TimeUnit.SECONDS);
		}
		assertEquals(201, running.get().statusCode());
		var retried = upload(rejectedKey, "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(201, retried.statusCode(), retried.body());
		assertEquals(2, API.calls.get());
		verify(sources, times(3)).upload(anyString(), any());
	}

	/** 헤더 수신 후 본문이 멈추면 504와 실패 이력을 남기고 다음 접수도 정상 처리하는지 검증한다. */
	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void bodyTimeoutReturns504PreservesFailureAndAllowsTheNextIntake(int prefixBytes) throws Exception {
		API.holdBody = true;
		API.bodyPrefixBytes = prefixBytes;
		UUID key = UUID.randomUUID();
		var running = CompletableFuture.supplyAsync(() -> {
			try {
				return upload(key, "PLAN", "report.pdf", PDF, 1, token);
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		});
		try {
			assertTrue(API.headersSent.await(5, TimeUnit.SECONDS));
			var response = running.get(5, TimeUnit.SECONDS);
			assertEquals(504, response.statusCode(), response.body());
			assertEquals("REPORT-0031", json(response).get("code").asString());
			long report = json(response).get("reportId").asLong();
			assertEquals("FAILED", jdbc.queryForObject(
					"SELECT process_status FROM report_parse_attempt WHERE report_id=?", String.class, report));
			assertNotNull(jdbc.queryForObject("SELECT cleanup_after FROM report_file WHERE report_id=?",
					java.sql.Timestamp.class, report));
			assertEquals(1, API.calls.get());
		} finally {
			API.release.countDown();
			running.get(5, TimeUnit.SECONDS);
		}
		API.holdBody = false;
		API.bodyPrefixBytes = 0;
		assertEquals(504, upload(key, "PLAN", "report.pdf", PDF, 1, token).statusCode());
		assertEquals(1, API.calls.get());
		var next = upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(201, next.statusCode(), next.body());
		assertEquals(2, API.calls.get());
	}

	/** 파싱 실패로 접수가 끝나도 처리 자리를 반환해 다음 신규 접수를 허용하는지 검증한다. */
	@ParameterizedTest
	@CsvSource({"PDF_CORRUPTED,422", "PARSER_FAILED,500", "PARSER_TIMEOUT,504"})
	void parserFailuresReleaseTheIntakePermit(PdfParseErrorCode error, int status) throws Exception {
		API.error = error;
		var failed = upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(status, failed.statusCode(), failed.body());
		API.error = null;
		var next = upload(UUID.randomUUID(), "PLAN", "report.pdf", PDF, 1, token);
		assertEquals(201, next.statusCode(), next.body());
	}

	private HttpResponse<String> upload(UUID key, String type, String filename, byte[] bytes, int files, String access)
			throws Exception {
		return upload(key, type, filename, bytes, files, access, "application/pdf");
	}

	private HttpResponse<String> upload(
		UUID key,
		String type,
		String filename,
		byte[] bytes,
		int files,
		String access,
		String mediaType
	) throws Exception {
		String boundary = "bareum-test-boundary";
		var body = new ByteArrayOutputStream();
		body.writeBytes(
				("--" + boundary + "\r\nContent-Disposition: form-data; name=\"reportType\"\r\n\r\n" + type + "\r\n")
						.getBytes(StandardCharsets.UTF_8));
		for (int i = 0; i < files; i++) {
			body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
					+ filename + "\"\r\nContent-Type: " + mediaType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			body.writeBytes(bytes);
			body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
		}
		body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
		var request = HttpRequest.newBuilder(uri("/api/v1/reports"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
		if (key != null)
			request.header("Idempotency-Key", key.toString());
		if (access != null)
			request.header("Authorization", "Bearer " + access);
		return send(request);
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> preview(long reportId, String access) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/v1/reports/" + reportId + "/preview")).GET();
		if (access != null) {
			request.header("Authorization", "Bearer " + access);
		}
		return send(request);
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private JsonNode json(HttpResponse<String> response) {
		return mapper.readTree(response.body());
	}
}
