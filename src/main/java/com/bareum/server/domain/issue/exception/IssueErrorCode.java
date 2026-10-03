package com.bareum.server.domain.issue.exception;

import com.bareum.server.global.exception.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum IssueErrorCode implements BaseErrorCode {
	INVALID_CHANGES(HttpStatus.INTERNAL_SERVER_ERROR, "ISSUE-0002", "이슈 변경 데이터가 올바르지 않습니다."),
	ISSUE_NOT_UNPROCESSED(HttpStatus.CONFLICT, "ISSUE-0007", "미처리 이슈만 무시할 수 있습니다."),
	ISSUE_NOT_IGNORED(HttpStatus.CONFLICT, "ISSUE-0008", "무시 상태의 이슈만 무시를 해제할 수 있습니다."),
	INVALID_GROUP_TARGET(HttpStatus.INTERNAL_SERVER_ERROR, "ISSUE-0009", "이슈 그룹의 대상 범위와 ID가 올바르지 않습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

	@Override
	public Level getLogLevel() {
		return httpStatus.is5xxServerError() ? Level.ERROR : Level.INFO;
	}
}
