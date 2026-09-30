package com.bareum.server.domain.issue.exception;

import com.bareum.server.global.exception.BusinessException;
import com.bareum.server.global.exception.Domain;

public class IssueException extends BusinessException {

	public IssueException(IssueErrorCode errorCode) {
		super(Domain.ISSUE, errorCode);
	}
}
