package com.bareum.server.domain.report.enums;

/** AI 파싱 오류 이후 요청 수정, 새 시도 생성, 처리 중단 중 어떤 행동이 필요한지 나타낸다. */
public enum PdfParseErrorAction {

	CREATE_NEW_ATTEMPT,
	FIX_REQUEST,
	STOP
}
