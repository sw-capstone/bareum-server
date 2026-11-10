package com.bareum.server.domain.report.intake;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

/** 보고서 파일의 크기와 형식을 검증하고 원본 해시와 요청 지문을 계산한다. */
@Component
public class ReportUploadValidator {
	public PreparedReportFile prepare(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ReportException(ReportErrorCode.FILE_METADATA_REQUIRED);
		}
		if (file.getSize() > ReportIntakePolicy.MAX_FILE_SIZE_BYTES) {
			throw new ReportException(ReportErrorCode.FILE_TOO_LARGE);
		}
		String filename = normalizeFilename(file.getOriginalFilename());
		FileFormat format = Arrays.stream(FileFormat.values())
				.filter(value -> filename.toLowerCase(Locale.ROOT).endsWith("." + value.extension()))
				.findFirst()
				.orElseThrow(() -> new ReportException(ReportErrorCode.UNSUPPORTED_FILE_TYPE));
		String contentType = file.getContentType();
		if (contentType != null && !contentType.isBlank()
				&& !format.contentType().equalsIgnoreCase(contentType)
				&& !"application/octet-stream".equalsIgnoreCase(contentType)
				&& !(format == FileFormat.HWPX && "application/zip".equalsIgnoreCase(contentType))) {
			throw new ReportException(ReportErrorCode.UNSUPPORTED_FILE_TYPE);
		}
		byte[] bytes;
		try (var stream = file.getInputStream()) {
			bytes = stream.readNBytes((int)ReportIntakePolicy.MAX_FILE_SIZE_BYTES + 1);
		} catch (IOException exception) {
			throw new ReportException(ReportErrorCode.FILE_READ_FAILED);
		}
		if (bytes.length > ReportIntakePolicy.MAX_FILE_SIZE_BYTES) {
			throw new ReportException(ReportErrorCode.FILE_TOO_LARGE);
		}
		if (bytes.length != file.getSize()) {
			throw new ReportException(ReportErrorCode.FILE_METADATA_REQUIRED);
		}
		boolean signatureMatches = format == FileFormat.PDF
				? bytes.length >= 5 && "%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII))
				: bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
		if (!signatureMatches) {
			throw new ReportException(ReportErrorCode.UNSUPPORTED_FILE_TYPE);
		}
		return new PreparedReportFile(bytes, filename, digest(bytes), format);
	}

	public String fingerprint(ReportType type, PreparedReportFile pdf) {
		String input = type.name() + "\0" + pdf.filename() + "\0" + pdf.sizeBytes() + "\0" + pdf.sha256();
		return digest(input.getBytes(StandardCharsets.UTF_8));
	}

	private String normalizeFilename(String original) {
		if (original == null || original.codePoints().anyMatch(Character::isISOControl)) {
			throw new ReportException(ReportErrorCode.FILE_METADATA_REQUIRED);
		}
		String path = original.replace('\\', '/');
		String name = Normalizer.normalize(path.substring(path.lastIndexOf('/') + 1).strip(), Normalizer.Form.NFC);
		if (name.isBlank() || name.codePointCount(0, name.length()) > 255) {
			throw new ReportException(ReportErrorCode.FILE_METADATA_REQUIRED);
		}
		return name;
	}

	private static String digest(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable");
		}
	}
}
