package com.bareum.server.domain.report.exception;

import com.bareum.server.global.exception.BusinessException;
import com.bareum.server.global.exception.Domain;

public class ReportException extends BusinessException {

	public ReportException(ReportErrorCode errorCode) {
		super(Domain.REPORT, errorCode);
	}
}
