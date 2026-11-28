package com.bareum.server.domain.report.enums;

import java.util.Locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 보고서 파일의 형식과 저장소에 설정할 미디어 타입을 구분한다. */
@Getter
@RequiredArgsConstructor
public enum FileFormat {
	HWPX("application/hwp+zip"),
	DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
	PDF("application/pdf");

	private final String mediaType;

	public String extension() {
		return "." + name().toLowerCase(Locale.ROOT);
	}
}
