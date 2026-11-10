package com.bareum.server.domain.report.intake;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import com.bareum.server.domain.report.enums.FileFormat;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** 검증한 원본 바이트를 업로드가 끝날 때까지 요청 안에서만 보관한다. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class PreparedReportFile {
	private final byte[] bytes;
	private final String filename;
	private final String sha256;
	private final FileFormat format;

	public FileFormat format() { return format; }

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
