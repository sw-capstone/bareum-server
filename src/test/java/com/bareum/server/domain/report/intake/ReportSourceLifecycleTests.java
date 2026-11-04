package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportParseAttempt;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.source.UploadedReportFile;

/** 원본 파일의 보관 기한과 접수 취소에 따른 상태 변경을 검증한다. */
class ReportSourceLifecycleTests {
	@Test
	void sourceStoresNormalizedIdentityVersionAndThirtyDayExpiry() {
		var source = new UploadedReportFile(FileFormat.PDF, "reports/source.pdf", "보고서.pdf",
				20_000_000, "A".repeat(64), "version-1", IntakeFixtures.NOW);
		Report report = Report.createFromFile(IntakeFixtures.member(), ReportType.RESULT, source);
		assertEquals(IntakeFixtures.HASH, report.getSourceSha256());
		assertEquals("version-1", report.getSourceVersionId());
		assertEquals(IntakeFixtures.NOW.plus(Duration.ofDays(30)), report.getSourceExpiresAt());
		assertEquals("보고서.pdf", report.getTitle());
		assertFalse(source.toString().contains("reports/source.pdf"));
	}

	@Test
	void twentyMillionBytesIsInclusiveAndNextByteIsRejected() {
		assertDoesNotThrow(() -> new UploadedReportFile(FileFormat.PDF, "key", "report.pdf", 20_000_000,
				IntakeFixtures.HASH, null, IntakeFixtures.NOW));
		ReportException error = assertThrows(ReportException.class,
				() -> new UploadedReportFile(FileFormat.PDF, "key", "report.pdf", 20_000_001,
						IntakeFixtures.HASH, null, IntakeFixtures.NOW));
		assertEquals(ReportErrorCode.FILE_TOO_LARGE, error.getBaseErrorCode());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "../report.pdf", "C:\\report.pdf", "report\n.pdf"})
	void metadataRejectsEmptyOrUnnormalizedFilenames(String filename) {
		assertThrows(ReportException.class, () -> new UploadedReportFile(FileFormat.PDF, "key", filename,
				100, IntakeFixtures.HASH, null, IntakeFixtures.NOW));
	}

	@Test
	void invalidHashAndZeroBytesCannotCreateAFileReport() {
		assertThrows(ReportException.class, () -> new UploadedReportFile(FileFormat.PDF, "key", "report.pdf",
				100, "not-a-sha256", null, IntakeFixtures.NOW));
		assertThrows(ReportException.class, () -> new UploadedReportFile(FileFormat.PDF, "key", "report.pdf",
				0, IntakeFixtures.HASH, null, IntakeFixtures.NOW));
	}

	@Test
	void cancellingIntakeBlocksAnalysisAndPreservesTheParsedBody() throws Exception {
		Report report = IntakeFixtures.report(IntakeFixtures.member());
		var content = IntakeFixtures.content(report);
		var body = content.getContent();
		content.freeze();
		report.cancelIntake(IntakeFixtures.NOW.plusSeconds(10));
		report.cancelIntake(IntakeFixtures.NOW.plusSeconds(20));
		assertEquals(IntakeFixtures.NOW.plusSeconds(10), report.getIntakeCancelledAt());
		assertEquals(IntakeFixtures.NOW.plusSeconds(10), report.getSourceCleanupAfter());
		assertNull(report.getSourceDeletedAt());
		assertEquals(body, content.getContent());
		ReportException error = assertThrows(ReportException.class,
				() -> ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1"));
		assertEquals(ReportErrorCode.INTAKE_CANCELLED, error.getBaseErrorCode());
		assertThrows(ReportException.class, () -> ReportParseAttempt.request(report));
	}

	@Test
	void sourceExpiryAndConfirmedDeletionAreSeparateFromIntakeCancellation() throws Exception {
		Report report = IntakeFixtures.report(IntakeFixtures.member());
		var content = IntakeFixtures.content(report);
		content.freeze();
		report.markSourceDeleted(report.getSourceExpiresAt());
		assertDoesNotThrow(() -> ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1"));
		assertNull(report.getIntakeCancelledAt());
	}

	@Test
	void cleanupCanBeDeferredUntilTheRemoteExecutionDeadline() {
		Report report = IntakeFixtures.report(IntakeFixtures.member());
		report.scheduleSourceCleanup(IntakeFixtures.NOW.plusSeconds(320));
		assertEquals(IntakeFixtures.NOW.plusSeconds(320), report.getSourceCleanupAfter());
		assertNull(report.getSourceDeletedAt());
	}

	@Test
	void cancellationCannotBringForwardADeferredCleanupDeadline() {
		Report report = IntakeFixtures.report(IntakeFixtures.member());
		report.scheduleSourceCleanup(IntakeFixtures.NOW.plusSeconds(320));
		report.cancelIntake(IntakeFixtures.NOW.plusSeconds(10));
		assertEquals(IntakeFixtures.NOW.plusSeconds(320), report.getSourceCleanupAfter());
	}
}
