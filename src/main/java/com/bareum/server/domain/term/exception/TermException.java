package com.bareum.server.domain.term.exception;

import com.bareum.server.global.exception.BusinessException;
import com.bareum.server.global.exception.Domain;

public class TermException extends BusinessException {

	public TermException(TermErrorCode errorCode) {
		super(Domain.TERM, errorCode);
	}
}
