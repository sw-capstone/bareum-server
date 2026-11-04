package com.bareum.server.domain.report.intake;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** 검증한 원본 바이트를 업로드가 끝날 때까지 요청 안에서만 보관한다. */
public final class PreparedPdf {
	private final byte[] bytes;
	private final String filename;
	private final String sha256;

	PreparedPdf(byte[] bytes, String filename, String sha256) {
		this.bytes = bytes;
		this.filename = filename;
		this.sha256 = sha256;
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
}
