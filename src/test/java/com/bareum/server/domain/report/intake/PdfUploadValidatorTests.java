package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

/** PDF 업로드의 파일 제한, 파일명 정리와 요청 지문 계산을 검증한다. */
class PdfUploadValidatorTests {
	private final PdfUploadValidator validator = new PdfUploadValidator();
	private final byte[] bytes = "%PDF-1.7\nfixture".getBytes(StandardCharsets.US_ASCII);

	@Test
	void normalizesBrowserPathsAndHashesTheActualOriginalBytes() throws Exception {
		var pdf = validator.prepare(file("C:\\fakepath\\보고서.PDF", bytes));
		assertEquals("보고서.PDF", pdf.filename());
		assertArrayEquals(bytes, pdf.openStream().readAllBytes());
		assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), pdf.sha256());
		assertEquals(bytes.length, pdf.sizeBytes());
	}

	@Test
	void acceptsExactlyTwentyMillionBytesAndRejectsOneMore() {
		byte[] atLimit = Arrays.copyOf(bytes, 20_000_000);
		assertEquals(20_000_000, validator.prepare(file("report.pdf", atLimit)).sizeBytes());
		rejects(ReportErrorCode.FILE_TOO_LARGE, file("report.pdf", Arrays.copyOf(bytes, 20_000_001)));
	}

	@Test
	void boundsReadingEvenWhenTheAdvertisedSizeIsFalse() throws Exception {
		var file = mock(MultipartFile.class);
		when(file.getSize()).thenReturn(10L);
		when(file.getOriginalFilename()).thenReturn("report.pdf");
		when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[20_000_001]));
		rejects(ReportErrorCode.FILE_TOO_LARGE, file);
	}

	@Test
	void rejectsAdvertisedSizeMismatch() throws Exception {
		var file = mock(MultipartFile.class);
		when(file.getSize()).thenReturn(100L);
		when(file.getOriginalFilename()).thenReturn("report.pdf");
		when(file.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
		rejects(ReportErrorCode.FILE_METADATA_REQUIRED, file);
	}

	@Test
	void rejectsEmptyAndNonPdfPayloads() {
		rejects(ReportErrorCode.FILE_METADATA_REQUIRED, file("report.pdf", new byte[0]));
		rejects(ReportErrorCode.UNSUPPORTED_FILE_TYPE,
				file("report.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)));
		rejects(ReportErrorCode.UNSUPPORTED_FILE_TYPE, file("report.docx", bytes));
		rejects(ReportErrorCode.UNSUPPORTED_FILE_TYPE, new MockMultipartFile("file", "report.pdf", "image/png", bytes));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "folder/", "report\r\n.pdf", "report\u0000.pdf"})
	void rejectsUnusableFilenames(String name) {
		rejects(ReportErrorCode.FILE_METADATA_REQUIRED, file(name, bytes));
	}

	@Test
	void fingerprintDistinguishesTypeFilenameAndBytesButIgnoresClientPath() {
		var original = validator.prepare(file("report.pdf", bytes));
		String fingerprint = validator.fingerprint(ReportType.PLAN, original);
		assertEquals(fingerprint,
				validator.fingerprint(ReportType.PLAN, validator.prepare(file("/tmp/report.pdf", bytes))));
		assertNotEquals(fingerprint, validator.fingerprint(ReportType.RESULT, original));
		assertNotEquals(fingerprint,
				validator.fingerprint(ReportType.PLAN, validator.prepare(file("other.pdf", bytes))));
		var changed = validator.prepare(file("report.pdf", "%PDF-1.7\nchanged".getBytes(StandardCharsets.US_ASCII)));
		assertNotEquals(fingerprint, validator.fingerprint(ReportType.PLAN, changed));
	}

	private MultipartFile file(String name, byte[] content) {
		return new MockMultipartFile("file", name, "application/pdf", content);
	}

	private void rejects(ReportErrorCode code, MultipartFile file) {
		assertEquals(code, assertThrows(ReportException.class, () -> validator.prepare(file)).getBaseErrorCode());
	}
}
