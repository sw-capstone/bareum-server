package com.bareum.server.domain.analysis.exception;

import com.bareum.server.global.exception.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AnalysisErrorCode implements BaseErrorCode {
	INPUT_NOT_FROZEN(HttpStatus.CONFLICT, "ANALYSIS-0001", "분석 기준으로 고정된 본문만 분석할 수 있습니다."),
	ANALYSIS_NOT_PENDING(HttpStatus.CONFLICT, "ANALYSIS-0002", "대기 중인 분석만 시작할 수 있습니다."),
	ANALYSIS_NOT_PROCESSING(HttpStatus.CONFLICT, "ANALYSIS-0003", "진행 중인 분석만 결과를 반영할 수 있습니다."),
	ANALYSIS_ALREADY_FINISHED(HttpStatus.CONFLICT, "ANALYSIS-0004", "이미 종료된 분석입니다."),
	INVALID_SCORE(HttpStatus.INTERNAL_SERVER_ERROR, "ANALYSIS-0005", "분석 점수 데이터가 올바르지 않습니다."),
	INVALID_EXECUTION_TIME(HttpStatus.INTERNAL_SERVER_ERROR, "ANALYSIS-0006", "분석 처리 시각이 올바르지 않습니다."),
	STEP_NOT_PENDING(HttpStatus.CONFLICT, "ANALYSIS-0007", "대기 중인 분석 단계에서만 가능한 작업입니다."),
	STEP_NOT_PROCESSING(HttpStatus.CONFLICT, "ANALYSIS-0008", "진행 중인 분석 단계만 종료할 수 있습니다."),
	STEP_ALREADY_FINISHED(HttpStatus.CONFLICT, "ANALYSIS-0009", "이미 종료된 분석 단계입니다."),
	INVALID_RESULT_DETAIL(HttpStatus.INTERNAL_SERVER_ERROR, "ANALYSIS-0010", "분석 상세 결과가 올바르지 않습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

	@Override
	public Level getLogLevel() {
		return httpStatus.is5xxServerError() ? Level.ERROR : Level.INFO;
	}
}
