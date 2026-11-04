package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.PdfParseErrorCode;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.parsing.PdfParseRequest;
import com.bareum.server.domain.report.parsing.PdfParseResponse;
import com.bareum.server.domain.report.parsing.PdfParser;
import com.bareum.server.domain.report.service.ReportIntakeMaintenanceService;
import com.bareum.server.domain.report.service.ReportUploadService;
import com.bareum.server.domain.report.service.command.ReportIntakeCommandService;
import com.bareum.server.domain.report.service.command.ReportSourceCommandService;
import com.bareum.server.domain.report.source.ReportSourceStore;
import com.bareum.server.domain.report.source.UploadedReportFile;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 실제 DB에서 재시도 이력, 중단 복구, 원본 삭제의 실패와 경합을 검증한다. */
@SpringBootTest(properties = {"report.upload.bucket=fixture-bucket", "report.upload.parser-token=fixture-token",
	"report.upload.parser-endpoint=http://127.0.0.1/internal/v1/pdf-parses"})
class ReportIntakeMaintenanceIntegrationTests {
	private final AtomicReference<Instant> now = new AtomicReference<>();
	private final JsonMapper mapper = JsonMapper.builder().build();
	@Autowired
	private MemberRepository members;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ReportUploadService uploads;
	@Autowired
	private ReportIntakeCommandService commands;
	@Autowired
	private ReportIntakeMaintenanceService maintenance;
	@MockitoSpyBean
	private ReportSourceCommandService sourceCommands;
	@MockitoBean
	private ReportSourceStore sources;
	@MockitoBean
	private PdfParser parser;
	@MockitoBean
	private Clock clock;
	private Member member;

	/** 고정 시계와 사용자, 원본 저장소 및 파서 응답을 각 검증 전에 준비한다. */
	@BeforeEach
	void setUp() {
		now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
		when(clock.instant()).thenAnswer(i -> now.get());
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		member = members.saveAndFlush(
				Member.createLocal("maintenance-" + UUID.randomUUID() + "@example.invalid", "tester", "hash"));
		when(sources.newObjectKey(anyLong()))
				.thenAnswer(i -> "reports/" + i.getArgument(0) + "/" + UUID.randomUUID() + ".pdf");
		when(sources.upload(anyString(), any())).thenAnswer(i -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			PreparedPdf pdf = i.getArgument(1);
			return new UploadedReportFile(FileFormat.PDF, i.getArgument(0), pdf.filename(), pdf.sizeBytes(),
					pdf.sha256(), "version-1", now.get());
		});
		when(sources.presign(any())).thenAnswer(i -> new ReportSourceStore.DownloadSource(
				URI.create("https://fixture.invalid/source?generation=" + UUID.randomUUID()),
				now.get().plusSeconds(600)));
		when(parser.parse(any(), any())).thenAnswer(i -> success(i.getArgument(0)));
		doAnswer(i -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			return null;
		}).when(sources).delete(anyString(), anyString());
	}

	/** 검증에서 생성한 접수와 보고서 관련 기록을 외래 키 순서에 맞춰 정리한다. */
	@AfterEach
	void cleanUp() {
		long id = member.getId();
		jdbc.update("DELETE FROM report_intake_request WHERE member_id=?", id);
		jdbc.update("DELETE FROM report_parse_attempt WHERE report_id IN (SELECT id FROM report WHERE member_id=?)",
				id);
		jdbc.update("DELETE FROM report_content WHERE report_id IN (SELECT id FROM report WHERE member_id=?)", id);
		jdbc.update("DELETE FROM report WHERE member_id=?", id);
		jdbc.update("DELETE FROM member WHERE id=?", id);
	}

	/** 일시적 오류만 새 URL과 파싱 시도로 재요청하고 원본은 한 번만 업로드하는지 검증한다. */
	@ParameterizedTest
	@EnumSource(value = PdfParseErrorCode.class,
		names = {"SOURCE_URL_EXPIRED", "SOURCE_DOWNLOAD_TEMPORARY_FAILURE", "CAPACITY_EXCEEDED"})
	void retriesOnlyTemporaryFailuresWithNewIdsAndUrlsButTheSameOriginal(PdfParseErrorCode code) {
		doAnswer(i -> failure(i.getArgument(0), code, 0))
				.doAnswer(i -> success(i.getArgument(0)))
				.when(parser)
				.parse(any(), any());
		var result = upload();
		assertEquals(201, result.status());
		long report = result.body().get("reportId").asLong();
		assertEquals(List.of("FAILED", "SUCCEEDED"), jdbc.queryForList(
				"SELECT process_status FROM report_parse_attempt WHERE report_id=? ORDER BY id", String.class, report));
		assertEquals(code.name(), jdbc.queryForObject(
				"SELECT error_code FROM report_parse_attempt WHERE report_id=? ORDER BY id LIMIT 1", String.class,
				report));
		var requests = org.mockito.ArgumentCaptor.forClass(PdfParseRequest.class);
		verify(parser, times(2)).parse(requests.capture(), any());
		var first = requests.getAllValues().getFirst();
		var second = requests.getAllValues().getLast();
		assertNotEquals(first.parseAttemptId(), second.parseAttemptId());
		assertEquals(first.source().objectKey(), second.source().objectKey());
		assertNotEquals(first.source().downloadUrl(), second.source().downloadUrl());
		verify(sources, times(1)).upload(anyString(), any());
	}

	/** 추가 시도 1회 후에는 중단하고 같은 멱등 요청에 최종 실패를 재사용하는지 검증한다. */
	@Test
	void stopsAfterOneAdditionalAttemptAndReplaysTheFinalFailure() {
		doAnswer(i -> failure(i.getArgument(0), PdfParseErrorCode.CAPACITY_EXCEEDED, 0))
				.when(parser)
				.parse(any(), any());
		var key = UUID.randomUUID();
		var result = upload(key);
		assertEquals(503, result.status());
		assertEquals(mapper.readTree(result.body().toString()), mapper.readTree(upload(key).body().toString()));
		verify(parser, times(2)).parse(any(), any());
		assertEquals(2,
				jdbc.queryForObject(
						"SELECT count(*) FROM report_parse_attempt WHERE report_id=? AND process_status='FAILED'",
						Integer.class,
						result.body().get("reportId").asLong()));
	}

	/** 문서 손상과 파서 자체 실패, 실행 제한시간 초과에는 자동 재시도하지 않는지 검증한다. */
	@ParameterizedTest
	@EnumSource(value = PdfParseErrorCode.class, names = {"PARSER_FAILED", "PARSER_TIMEOUT", "PDF_CORRUPTED"})
	void doesNotRetryDefinitiveParserFailures(PdfParseErrorCode code) {
		doAnswer(i -> failure(i.getArgument(0), code, null)).when(parser).parse(any(), any());
		assertTrue(upload().status() >= 400);
		verify(parser, times(1)).parse(any(), any());
	}

	/** 원격 종료가 불명확한 타임아웃은 재호출하지 않고 원본 삭제를 370초 미루는지 검증한다. */
	@Test
	void uncertainTransportFailureIsNotRetriedAndDefersDeletionFor370Seconds() {
		doThrow(new ReportException(ReportErrorCode.PDF_PARSE_REQUEST_TIMEOUT)).when(parser).parse(any(), any());
		var result = upload();
		assertEquals(504, result.status());
		verify(parser, times(1)).parse(any(), any());
		long report = result.body().get("reportId").asLong();
		assertEquals(now.get().plusSeconds(370).toEpochMilli(), jdbc.queryForObject(
				"SELECT source_cleanup_after FROM report WHERE id=?", java.sql.Timestamp.class, report)
				.toInstant()
				.toEpochMilli());
		maintenance.deleteSources();
		verify(sources, never()).delete(anyString(), anyString());
	}

	/** 서버가 요구한 재시도 대기시간이 접수의 남은 기한보다 길면 재호출하지 않는지 검증한다. */
	@Test
	void refusesRetryAfterThatCannotFitInsideTheOriginalDeadline() {
		doAnswer(i -> failure(i.getArgument(0), PdfParseErrorCode.CAPACITY_EXCEEDED, 1000))
				.when(parser)
				.parse(any(), any());
		assertEquals(503, upload().status());
		verify(parser, times(1)).parse(any(), any());
	}

	/** 재시도가 최초 기한을 공유하고 기한 이후 성공 응답으로 본문을 생성하지 않는지 검증한다. */
	@Test
	void retrySharesTheOriginalBudgetAndLateSuccessCannotCreateContent() {
		doAnswer(i -> {
			advance(330);
			return failure(i.getArgument(0), PdfParseErrorCode.SOURCE_URL_EXPIRED, null);
		}).doAnswer(i -> {
			Duration remaining = i.getArgument(1);
			assertEquals(10, remaining.toSeconds());
			advance(20);
			return success(i.getArgument(0));
		}).when(parser).parse(any(), any());
		var result = upload();
		assertEquals(504, result.status());
		assertEquals("REPORT-0057", result.body().get("code").asString());
		assertEquals(0, contentCount(result.body().get("reportId").asLong()));
		verify(parser, times(2)).parse(any(), any());
	}

	/** 중단된 업로드를 복구하고 누락된 원본 버전을 DB에 기록한 뒤 삭제하는지 검증한다. */
	@Test
	void recoversAnInterruptedUploadAndFindsItsUnrecordedVersionBeforeDeletion() {
		var request =
				commands.reserve(member.getId(), UUID.randomUUID(), IntakeFixtures.HASH, "reports/interrupted.pdf");
		advance(341);
		maintenance.recoverInterruptedRequests();
		assertEquals("FAILED", requestValue(request.requestId(), "status", String.class));
		assertEquals(504, requestValue(request.requestId(), "response_status", Integer.class));
		maintenance.deleteSources();
		verify(sources, never()).findVersions(anyString());
		advance(370);
		when(sources.findVersions("reports/interrupted.pdf")).thenReturn(List.of("recovered-version"));
		doAnswer(i -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			assertEquals("recovered-version", requestValue(request.requestId(), "source_version_id", String.class));
			return null;
		}).when(sources).delete("reports/interrupted.pdf", "recovered-version");
		maintenance.deleteSources();
		assertNotNull(requestValue(request.requestId(), "source_deleted_at", java.sql.Timestamp.class));
		verify(sources).delete("reports/interrupted.pdf", "recovered-version");
	}

	/** 복구로 확정한 실패를 늦게 도착한 파싱 성공이 덮어쓰지 못하는지 검증한다. */
	@Test
	void interruptedParseAndLateRemoteSuccessCannotOverwriteEachOther() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		doAnswer(i -> {
			entered.countDown();
			assertTrue(release.await(10, TimeUnit.SECONDS));
			return success(i.getArgument(0));
		}).when(parser).parse(any(), any());
		var running = CompletableFuture.supplyAsync(this::upload);
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS));
			advance(341);
			maintenance.recoverInterruptedRequests();
		} finally {
			release.countDown();
		}
		var result = running.get(10, TimeUnit.SECONDS);
		assertEquals(504, result.status());
		long report = result.body().get("reportId").asLong();
		assertEquals(0, contentCount(report));
		assertEquals("FAILED", jdbc.queryForObject("SELECT process_status FROM report_parse_attempt WHERE report_id=?",
				String.class, report));
		advance(370);
		maintenance.recoverInterruptedRequests();
		maintenance.deleteSources();
		verify(sources).delete(anyString(), eq("version-1"));
	}

	/** 완료된 접수는 복구 대상에서 제외하고 취소 원본의 삭제 실패는 재처리하는지 검증한다. */
	@Test
	void completedIntakeIsPreservedByRecoveryAndCancelledSourceIsRetriedAfterDeletionFailure() {
		var result = upload();
		long report = result.body().get("reportId").asLong();
		advance(341);
		maintenance.recoverInterruptedRequests();
		assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM report_intake_request WHERE report_id=?",
				String.class, report));
		assertFalse(sourceCommands.request(member.getId(), report));
		doThrow(new ReportException(ReportErrorCode.SOURCE_STORAGE_FAILED)).doNothing()
				.when(sources)
				.delete(anyString(), anyString());
		maintenance.deleteSources();
		assertNull(jdbc.queryForObject("SELECT source_deleted_at FROM report WHERE id=?", java.sql.Timestamp.class,
				report));
		advance(86_400);
		assertEquals(0, commands.purgeExpiredRequests(now.get()));
		maintenance.deleteSources();
		assertTrue(sourceCommands.request(member.getId(), report));
		verify(sources, times(2)).delete(anyString(), eq("version-1"));
		assertEquals(1, contentCount(report));
		assertEquals(1, commands.purgeExpiredRequests(now.get()));
	}

	/** S3 삭제 후 DB 기록에 실패해도 같은 버전을 다시 삭제해 완료할 수 있는지 검증한다. */
	@Test
	void repeatsSpecificVersionDeletionWhenTheS3DeleteSucceededButDbRecordingFailed() {
		long report = upload().body().get("reportId").asLong();
		sourceCommands.request(member.getId(), report);
		doThrow(new org.springframework.dao.DataIntegrityViolationException("fixture failure")).doCallRealMethod()
				.when(sourceCommands)
				.markDeleted(any());
		maintenance.deleteSources();
		assertNull(jdbc.queryForObject("SELECT source_deleted_at FROM report WHERE id=?", java.sql.Timestamp.class,
				report));
		maintenance.deleteSources();
		assertNotNull(jdbc.queryForObject("SELECT source_deleted_at FROM report WHERE id=?", java.sql.Timestamp.class,
				report));
		verify(sources, times(2)).delete(anyString(), eq("version-1"));
	}

	/** 보관 만료된 원본만 삭제하고 파싱한 본문과 접수 상태는 보존하는지 검증한다. */
	@Test
	void deletesExpiredOriginalWithoutDeletingTheParsedBody() {
		long report = upload().body().get("reportId").asLong();
		advance(30 * 86_400L);
		maintenance.deleteSources();
		assertNotNull(jdbc.queryForObject("SELECT source_deleted_at FROM report WHERE id=?", java.sql.Timestamp.class,
				report));
		assertNull(jdbc.queryForObject("SELECT intake_cancelled_at FROM report WHERE id=?", java.sql.Timestamp.class,
				report));
		assertEquals(1, contentCount(report));
	}

	/** 버전 조회 실패는 미완료로 남기고 다른 원본의 정리는 계속 진행하는지 검증한다. */
	@Test
	void lookupFailureKeepsTheOrphanPendingAndDoesNotPreventOtherSourcesFromBeingDeleted() {
		var first = commands.reserve(member.getId(), UUID.randomUUID(), IntakeFixtures.HASH, "reports/first.pdf");
		var second = commands.reserve(member.getId(), UUID.randomUUID(), IntakeFixtures.HASH, "reports/second.pdf");
		advance(341);
		maintenance.recoverInterruptedRequests();
		advance(370);
		when(sources.findVersions("reports/first.pdf"))
				.thenThrow(new ReportException(ReportErrorCode.SOURCE_STORAGE_FAILED));
		when(sources.findVersions("reports/second.pdf")).thenReturn(List.of());
		maintenance.deleteSources();
		assertNull(requestValue(first.requestId(), "source_deleted_at", java.sql.Timestamp.class));
		assertNotNull(requestValue(second.requestId(), "source_deleted_at", java.sql.Timestamp.class));
		doReturn(List.of("null")).when(sources).findVersions("reports/first.pdf");
		maintenance.deleteSources();
		verify(sources).delete("reports/first.pdf", "null");
	}

	/** 재시도 대기 중 시도가 실패 상태여도 접수 취소와 원본 삭제를 막는지 검증한다. */
	@Test
	void failedAttemptWaitingForRetryCannotBeCancelledOrCollectedAsAnExpiredSource() {
		String key = "reports/retry-wait.pdf";
		var request = commands.reserve(member.getId(), UUID.randomUUID(), IntakeFixtures.HASH, key);
		var source = new UploadedReportFile(FileFormat.PDF, key, "report.pdf", 100L, IntakeFixtures.HASH, "version-1",
				now.get());
		commands.recordSource(request.requestId(), source);
		var work = commands.startParsing(request.requestId(), ReportType.PLAN, source);
		commands.recordRetryableFailure(work, PdfParseErrorCode.CAPACITY_EXCEEDED.name());
		jdbc.update("UPDATE report SET source_expires_at=? WHERE id=?",
				java.sql.Timestamp.from(now.get().minusSeconds(1)), work.reportId());
		var error = assertThrows(ReportException.class, () -> sourceCommands.request(member.getId(), work.reportId()));
		assertEquals(ReportErrorCode.SOURCE_IN_USE, error.getBaseErrorCode());
		maintenance.deleteSources();
		verify(sources, never()).delete(anyString(), anyString());
	}

	/** 버전이 여러 개인 원본은 일부 삭제에 실패해도 남은 버전을 찾아 정리하는지 검증한다. */
	@Test
	void interruptedCleanupWithSeveralVersionsKeepsTheUnrecordedKeyUntilAllVersionsAreDeleted() {
		String key = "reports/several-versions.pdf";
		var request = commands.reserve(member.getId(), UUID.randomUUID(), IntakeFixtures.HASH, key);
		advance(341);
		maintenance.recoverInterruptedRequests();
		advance(370);
		when(sources.findVersions(key)).thenReturn(List.of("v1", "v2")).thenReturn(List.of("v2"));
		doThrow(new ReportException(ReportErrorCode.SOURCE_STORAGE_FAILED)).doNothing().when(sources).delete(key, "v2");
		maintenance.deleteSources();
		assertNull(requestValue(request.requestId(), "source_version_id", String.class));
		assertNull(requestValue(request.requestId(), "source_deleted_at", java.sql.Timestamp.class));
		maintenance.deleteSources();
		assertNotNull(requestValue(request.requestId(), "source_deleted_at", java.sql.Timestamp.class));
		verify(sources, times(2)).delete(key, "v2");
	}

	private ReportIntakeResult upload() { return upload(UUID.randomUUID()); }

	private ReportIntakeResult upload(UUID key) {
		return uploads.upload(member.getId(), key, ReportType.PLAN,
				new MockMultipartFile("file", "report.pdf", "application/pdf",
						"%PDF-1.7\nstage-three-fixture".getBytes(StandardCharsets.US_ASCII)));
	}

	private void advance(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }

	private int contentCount(long report) {
		return jdbc.queryForObject("SELECT count(*) FROM report_content WHERE report_id=?", Integer.class, report);
	}

	private <T> T requestValue(long request, String column, Class<T> type) {
		return jdbc.queryForObject("SELECT " + column + " FROM report_intake_request WHERE id=?", type, request);
	}

	private PdfParseResponse failure(PdfParseRequest request, PdfParseErrorCode code, Integer retryAfter) {
		return new PdfParseResponse.Failed(request.reportId(), request.parseAttemptId(),
				new PdfParseResponse.Error(code, "fixture", code.getAction()), retryAfter);
	}

	private PdfParseResponse success(PdfParseRequest request) throws Exception {
		ObjectNode parsed = (ObjectNode)mapper
				.readTree(Files.readString(Path.of("src/test/resources/report/pdf-extraction-1.2.0.json")));
		((ObjectNode)parsed.get("document")).put("sha256", request.source().sha256());
		return new PdfParseResponse.Succeeded(request.reportId(), request.parseAttemptId(), "SUCCEEDED",
				now.get().atOffset(ZoneOffset.UTC),
				now.get().atOffset(ZoneOffset.UTC), com.bareum.server.domain.report.enums.ExtractionQuality.COMPLETE,
				parsed, null);
	}
}
