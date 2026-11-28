package com.bareum.server.domain.report.intake;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.bareum.server.domain.report.enums.FileFormat;

/** 접수 중 업로드할 파일의 바이트와 식별 정보를 보관한다. */
public final class PreparedReportFile {
	private final FileFormat format;
	private final byte[] bytes;
	private final String filename;
	private final String sha256;

	PreparedReportFile(FileFormat format, byte[] bytes, String filename) {
		this.format = format;
		this.bytes = bytes;
		this.filename = filename;
		this.sha256 = digest(bytes);
	}

	public FileFormat format() {
		return format;
	}

	public InputStream openStream() {
		return new ByteArrayInputStream(bytes);
	}

	public long sizeBytes() {
		return bytes.length;
	}

	public String filename() {
		return filename;
	}

	public String sha256() {
		return sha256;
	}

	static String digest(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable");
		}
	}
}
