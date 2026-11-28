package com.bareum.server.domain.report.intake;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

/** 원본 파일의 크기와 형식을 확인하고 접수 요청의 지문을 계산한다. */
@Component
public class ReportUploadValidator {
	private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] ZIP_HEADER = {
		0x50, 0x4b, 0x03, 0x04
	};

	public PreparedReportFile prepare(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ReportException(ReportErrorCode.FILE_METADATA_REQUIRED);
		}
		if (file.getSize() > ReportIntakePolicy.MAX_FILE_SIZE_BYTES) {
			throw new ReportException(ReportErrorCode.FILE_TOO_LARGE);
		}
		String filename = normalizeFilename(file.getOriginalFilename());
		FileFormat format = formatOf(filename);
		String contentType = file.getContentType();
		if (format == FileFormat.PDF && contentType != null && !contentType.isBlank()
				&& !contentType.equalsIgnoreCase(format.getMediaType())
				&& !contentType.equalsIgnoreCase("application/octet-stream")) {
			throw new ReportException(ReportErrorCode.UNSUPPORTED_FILE_TYPE);
		}
		byte[] bytes;
		try (InputStream stream = file.getInputStream()) {
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
		if (!startsWith(bytes, format == FileFormat.PDF ? PDF_HEADER : ZIP_HEADER)) {
			throw new ReportException(ReportErrorCode.UNSUPPORTED_FILE_TYPE);
		}
		return new PreparedReportFile(format, bytes, filename);
	}

	public String fingerprint(ReportType type, PreparedReportFile file) {
		String input = type.name() + "\0" + file.filename() + "\0" + file.sizeBytes() + "\0" + file.sha256();
		return PreparedReportFile.digest(input.getBytes(StandardCharsets.UTF_8));
	}

	static boolean hasPdfHeader(byte[] bytes) {
		return startsWith(bytes, PDF_HEADER);
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
	}

	private static FileFormat formatOf(String filename) {
		String lower = filename.toLowerCase(Locale.ROOT);
		for (FileFormat format : FileFormat.values()) {
			if (lower.endsWith(format.extension())) {
				return format;
			}
		}
		throw new ReportException(ReportErrorCode.UNSUPPORTED_FILE_TYPE);
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

}
