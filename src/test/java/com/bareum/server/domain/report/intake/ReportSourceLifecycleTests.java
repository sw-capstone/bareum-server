package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportFile;
import com.bareum.server.domain.report.entity.ReportParseAttempt;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.ReportFileRole;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.source.UploadedReportFile;

/** 원본과 변환 PDF의 식별 정보와 파일별 삭제 상태를 검증한다. */
class ReportSourceLifecycleTests {
	@Test
	void sourceStoresNormalizedIdentityAndVersion() {
		var source = new UploadedReportFile(FileFormat.PDF, "reports/source.pdf", "보고서.pdf",
				20_000_000, "A".repeat(64), "version-1", IntakeFixtures.NOW);
		Report report = Report.create(IntakeFixtures.member(), ReportType.RESULT);
		var file = ReportFile.original(report, source);
		assertEquals(IntakeFixtures.HASH, file.getSha256());
		assertEquals("version-1", file.getVersionId());
		assertEquals("보고서.pdf", file.getFilename());
		assertEquals(ReportFileRole.ORIGINAL, file.getRole());
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
		report.cancelIntake(IntakeFixtures.NOW.plusSeconds(10));
		report.cancelIntake(IntakeFixtures.NOW.plusSeconds(20));
		assertEquals(IntakeFixtures.NOW.plusSeconds(10), report.getIntakeCancelledAt());
		assertEquals(IntakeFixtures.NOW.plusSeconds(10), report.getDeletedAt());
		assertEquals(body, content.getContent());
		ReportException error = assertThrows(ReportException.class,
				() -> ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1"));
		assertEquals(ReportErrorCode.INTAKE_CANCELLED, error.getBaseErrorCode());
		assertThrows(ReportException.class, () -> ReportParseAttempt.request(report));
	}

	@Test
	void convertedPdfBelongsToTheSameReportAndNeedsASeparateObjectKey() {
		var report = IntakeFixtures.report(IntakeFixtures.member());
		var original = ReportFile.original(report, new UploadedReportFile(FileFormat.DOCX, "reports/original.docx",
				"원본.docx", 100, IntakeFixtures.HASH, "original-version", IntakeFixtures.NOW));
		PreparedReportFile pdf = new PreparedReportFile(FileFormat.PDF,
				"%PDF-1.7\nlifecycle-fixture".getBytes(StandardCharsets.US_ASCII), "원본.pdf");
		String convertedKey = "reports/display.pdf";
		ReportFile converted = ReportFile.reserveConvertedPdf(original, convertedKey, pdf);
		assertSame(report, converted.getReport());
		assertEquals(ReportFileRole.CONVERTED_PDF, converted.getRole());
		assertEquals(FileFormat.PDF, converted.getFormat());
		assertNull(converted.getVersionId());
		converted.recordPdfUpload(new UploadedReportFile(FileFormat.PDF, convertedKey, pdf.filename(), pdf.sizeBytes(),
				pdf.sha256(), "converted-version", IntakeFixtures.NOW));
		assertEquals("converted-version", converted.getVersionId());
		assertThrows(ReportException.class,
				() -> ReportFile.reserveConvertedPdf(original, original.getObjectKey(), pdf));
		var originalPdf = IntakeFixtures.original(report);
		assertThrows(ReportException.class, () -> ReportFile.reserveConvertedPdf(originalPdf, convertedKey, pdf));
	}

	@Test
	void cleanupCanBeDeferredUntilTheRemoteExecutionDeadline() {
		var file = IntakeFixtures.original(IntakeFixtures.report(IntakeFixtures.member()));
		var after = IntakeFixtures.NOW.plusSeconds(320);
		file.scheduleDeletion(IntakeFixtures.NOW, after);
		assertEquals(IntakeFixtures.NOW, file.getDeletedAt());
		assertEquals(after, file.getCleanupAfter());
		assertNull(file.getS3DeletedAt());
		assertThrows(ReportException.class, () -> file.markS3Deleted(IntakeFixtures.NOW));
		file.markS3Deleted(after);
		assertEquals(IntakeFixtures.NOW, file.getDeletedAt());
		assertEquals(after, file.getS3DeletedAt());
	}

	@Test
	void cancellationCannotBringForwardADeferredCleanupDeadline() {
		var file = IntakeFixtures.original(IntakeFixtures.report(IntakeFixtures.member()));
		var after = IntakeFixtures.NOW.plusSeconds(320);
		file.scheduleDeletion(IntakeFixtures.NOW, after);
		file.scheduleDeletion(IntakeFixtures.NOW.plusSeconds(10), IntakeFixtures.NOW.plusSeconds(10));
		assertEquals(after, file.getCleanupAfter());
	}
}
