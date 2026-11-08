package com.bareum.server.domain.report.exception;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

import com.bareum.server.global.exception.code.BaseErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 보고서 처리 오류의 HTTP 상태와 공개 오류 코드, 안내 메시지를 정의한다. */
@Getter
@RequiredArgsConstructor
public enum ReportErrorCode implements BaseErrorCode {

	// 보고서 입력과 원본 파일 관리
	FILE_METADATA_REQUIRED(HttpStatus.BAD_REQUEST, "REPORT-0001", "업로드한 파일 정보가 필요합니다."),
	INVALID_SOURCE_DELETION(HttpStatus.CONFLICT, "REPORT-0011", "원본 파일 삭제 정보를 기록할 수 없습니다."),

	// 공통 본문 생성과 편집
	INVALID_CONTENT(HttpStatus.BAD_REQUEST, "REPORT-0002", "보고서 본문이 올바르지 않습니다."),
	BASE_CONTENT_NOT_FROZEN(HttpStatus.CONFLICT, "REPORT-0003", "분석 기준으로 고정된 본문에서만 편집을 시작할 수 있습니다."),
	FROZEN_CONTENT_CANNOT_BE_EDITED(HttpStatus.CONFLICT, "REPORT-0004", "분석 기준으로 고정된 본문은 수정할 수 없습니다."),
	CONTENT_ALREADY_FROZEN(HttpStatus.CONFLICT, "REPORT-0005", "이미 고정된 본문입니다."),
	TEXT_SEGMENTATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0026", "보고서 본문의 문장 분리 결과를 처리할 수 없습니다."),

	// 파싱 시도 상태와 결과 기록
	PARSE_NOT_PENDING(HttpStatus.CONFLICT, "REPORT-0006", "대기 중인 파싱만 시작할 수 있습니다."),
	PARSE_NOT_PROCESSING(HttpStatus.CONFLICT, "REPORT-0007", "진행 중인 파싱만 종료할 수 있습니다."),
	INVALID_PARSE_RESULT(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0008", "파싱 결과 데이터가 올바르지 않습니다."),
	INVALID_PARSE_TIME(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0010", "파싱 처리 시각이 올바르지 않습니다."),

	// 결과 리포트 생성과 상태 관리
	RESULT_CONTENT_NOT_FROZEN(HttpStatus.CONFLICT, "REPORT-0012", "결과 리포트는 고정된 본문으로 생성해야 합니다."),
	INVALID_RESULT_STATE(HttpStatus.CONFLICT, "REPORT-0013", "현재 결과 리포트 상태에서는 처리할 수 없습니다."),
	INVALID_RESULT(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0014", "결과 리포트의 점수 또는 처리 시각이 올바르지 않습니다."),

	// PDF 파싱 API 통신
	PDF_PARSE_REQUEST_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "REPORT-0031", "PDF 파싱 API의 응답 대기시간을 초과했습니다."),
	PDF_PARSE_REQUEST_INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0032", "PDF 파싱 API 호출 대기가 중단되었습니다."),
	PDF_PARSE_CONNECTION_FAILED(HttpStatus.BAD_GATEWAY, "REPORT-0033", "PDF 파싱 API와 통신할 수 없습니다."),

	// PDF 파싱 API 응답 형식과 요청 일치 검사
	INVALID_PARSE_RESPONSE_FORMAT(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0015", "파서 응답의 JSON 형식이나 필수 필드가 올바르지 않습니다."),
	INVALID_PARSE_RESPONSE_METADATA(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0027", "파싱 응답의 상태, 시각 또는 추출 정보가 올바르지 않습니다."),
	UNSUPPORTED_PARSE_SCHEMA_VERSION(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0016", "지원하지 않는 파서 응답 형식 버전입니다."),
	PARSE_REPORT_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0009", "파싱 결과의 보고서가 일치하지 않습니다."),
	PARSE_ATTEMPT_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0028", "파싱 응답의 시도 식별자가 일치하지 않습니다."),
	PARSE_SOURCE_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0029", "파싱 응답의 원본 해시가 요청과 일치하지 않습니다."),
	INVALID_PARSE_ERROR_RESPONSE(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0030", "파싱 오류 응답의 상태 코드 또는 후속 행동이 올바르지 않습니다."),

	// PDF 문서 정보와 공통 본문 변환
	INVALID_PARSE_DOCUMENT_METADATA(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0017", "파서 응답의 문서 정보, 페이지 수 또는 파서 정보가 올바르지 않습니다."),
	INVALID_PARSED_CONTENT(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0025", "파서 응답으로 구성한 공통 본문이 검증을 통과하지 못했습니다."),

	// PDF 표와 셀 구조 검사
	INVALID_PARSE_TABLE_SIZE(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0018", "파싱된 표에는 하나 이상의 행과 열이 필요합니다."),
	PARSE_TABLE_ROWS_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0019", "파싱된 표의 텍스트와 원본 텍스트의 행·열 구성이 일치하지 않습니다."),
	INVALID_PARSE_CELL_RANGE(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0020", "파싱된 표의 셀 위치나 병합 범위가 올바르지 않습니다."),
	PARSE_CELL_OVERLAP(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0021", "파싱된 표에서 셀 영역이 서로 겹칩니다."),
	UNMAPPED_PARSE_TABLE_TEXT(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0022", "파싱된 표에 셀 정보와 연결되지 않은 텍스트가 있습니다."),

	// PDF 섹션 참조 검사
	INVALID_PARSE_SECTION_REFERENCE(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0023", "파싱된 섹션의 제목 또는 상위 블록을 찾을 수 없습니다."),
	CYCLIC_PARSE_SECTION_REFERENCE(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0024", "파싱된 섹션의 상위 블록을 찾는 경로가 순환합니다."),

	// PDF 접수와 멱등 요청, 원본 삭제
	FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "REPORT-0034", "파일 용량이 20MB를 초과했습니다."),
	INTAKE_IN_PROGRESS(HttpStatus.CONFLICT, "REPORT-0035", "동일한 접수 요청을 처리하고 있습니다."),
	IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "REPORT-0036", "같은 요청 키로 다른 내용을 접수할 수 없습니다."),
	INTAKE_CANCELLED(HttpStatus.CONFLICT, "REPORT-0037", "취소한 접수는 다시 사용할 수 없습니다."),
	REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "REPORT-0038", "보고서를 찾을 수 없습니다."),
	SOURCE_IN_USE(HttpStatus.CONFLICT, "REPORT-0039", "파싱 또는 분석을 시작한 원본은 이 화면에서 삭제할 수 없습니다."),
	INVALID_INTAKE_STATE(HttpStatus.CONFLICT, "REPORT-0040", "현재 접수 상태에서는 처리할 수 없습니다."),
	INVALID_INTAKE_RESULT(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0041", "접수 결과 기록이 올바르지 않습니다."),
	INTAKE_REPORT_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0042", "접수 기록과 보고서의 원본 또는 소유자가 일치하지 않습니다."),
	UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "REPORT-0043", "PDF 파일만 접수할 수 있습니다."),
	INVALID_FILE_COUNT(HttpStatus.BAD_REQUEST, "REPORT-0044", "file 필드로 PDF 파일 1개를 보내주세요."),
	FILE_READ_FAILED(HttpStatus.BAD_REQUEST, "REPORT-0045", "업로드한 파일을 읽을 수 없습니다."),
	SOURCE_STORAGE_FAILED(HttpStatus.BAD_GATEWAY, "REPORT-0046", "원본 파일 저장소와 통신할 수 없습니다."),
	UPLOAD_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "REPORT-0047", "보고서 접수 연동 설정이 준비되지 않았습니다."),
	PAGE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "REPORT-0048", "PDF는 최대 60페이지까지 접수할 수 있습니다."),
	PDF_ENCRYPTED(HttpStatus.UNPROCESSABLE_CONTENT, "REPORT-0049", "암호화된 PDF는 접수할 수 없습니다."),
	PDF_CORRUPTED(HttpStatus.UNPROCESSABLE_CONTENT, "REPORT-0050", "PDF 파일이 손상되었습니다."),
	NO_EXTRACTABLE_TEXT(HttpStatus.UNPROCESSABLE_CONTENT, "REPORT-0051", "PDF에서 추출할 수 있는 본문이 없습니다."),
	PDF_PARSER_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0052", "PDF 파싱 중 오류가 발생했습니다."),
	PDF_CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "REPORT-0053", "현재 PDF 처리 용량이 부족합니다."),
	PDF_PARSER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "REPORT-0054", "PDF 파싱 서버가 요청을 처리할 수 없습니다."),
	INTAKE_PERSISTENCE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT-0055", "접수 결과를 저장할 수 없습니다."),
	PDF_SOURCE_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "REPORT-0056", "PDF 파싱 서버가 원본을 가져올 수 없습니다."),
	INTAKE_PROCESSING_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "REPORT-0057", "보고서 접수 처리 기한을 초과했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

	@Override
	public Level getLogLevel() {
		return httpStatus.is5xxServerError() ? Level.ERROR : Level.INFO;
	}
}
