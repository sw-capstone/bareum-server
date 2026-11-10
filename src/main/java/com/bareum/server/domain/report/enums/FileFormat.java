package com.bareum.server.domain.report.enums;

// 업로드한 원본 파일의 형식(HWPX, DOCX, PDF)을 구분한다.
public enum FileFormat {
	HWPX("hwpx", "application/hwp+zip"),
	DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
	PDF("pdf", "application/pdf");

	private final String extension;
	private final String contentType;

	FileFormat(String extension, String contentType) {
		this.extension = extension;
		this.contentType = contentType;
	}

	public String extension() { return extension; }

	public String contentType() { return contentType; }
}
