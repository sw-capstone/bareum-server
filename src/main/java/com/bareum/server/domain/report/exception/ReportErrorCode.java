package com.bareum.server.domain.report.exception;

import com.bareum.server.global.exception.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReportErrorCode implements BaseErrorCode {
	FILE_METADATA_REQUIRED(HttpStatus.BAD_REQUEST, "REPORT-0001", "업로드한 파일 정보가 필요합니다."),
	INVALID_CONTENT(HttpStatus.BAD_REQUEST, "REPORT-0002", "보고서 본문이 올바르지 않습니다."),
	BASE_CONTENT_NOT_FROZEN(HttpStatus.CONFLICT, "REPORT-0003", "분석 기준으로 고정된 본문에서만 편집을 시작할 수 있습니다."),
	FROZEN_CONTENT_CANNOT_BE_EDITED(HttpStatus.CONFLICT, "REPORT-0004", "분석 기준으로 고정된 본문은 수정할 수 없습니다."),
	CONTENT_ALREADY_FROZEN(HttpStatus.CONFLICT, "REPORT-0005", "이미 고정된 본문입니다."),
	PARSE_NOT_PENDING(HttpStatus.CONFLICT, "REPORT-0006", "대기 중인 파싱만 시작할 수 있습니다."),
	PARSE_NOT_PROCESSING(HttpStatus.CONFLICT, "REPORT-0007", "진행 중인 파싱만 종료할 수 있습니다."),
	INVALID_PARSE_RESULT(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0008", "파싱 결과 데이터가 올바르지 않습니다."),
	PARSE_REPORT_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0009", "파싱 결과의 보고서가 일치하지 않습니다."),
	INVALID_PARSE_TIME(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0010", "파싱 처리 시각이 올바르지 않습니다."),
	INVALID_SOURCE_DELETION(HttpStatus.CONFLICT, "REPORT-0011", "원본 파일 삭제 정보를 기록할 수 없습니다."),
	RESULT_CONTENT_NOT_FROZEN(HttpStatus.CONFLICT, "REPORT-0012", "결과 리포트는 고정된 본문으로 생성해야 합니다."),
	INVALID_RESULT_STATE(HttpStatus.CONFLICT, "REPORT-0013", "현재 결과 리포트 상태에서는 처리할 수 없습니다."),
	INVALID_RESULT(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0014", "결과 리포트의 점수 또는 처리 시각이 올바르지 않습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

	@Override
	public Level getLogLevel() {
		return httpStatus.is5xxServerError() ? Level.ERROR : Level.INFO;
	}
}
