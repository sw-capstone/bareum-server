package com.bareum.server.domain.analysis.exception;

import com.bareum.server.global.exception.BusinessException;
import com.bareum.server.global.exception.Domain;

public class AnalysisException extends BusinessException {

	public AnalysisException(AnalysisErrorCode errorCode) {
		super(Domain.ANALYSIS, errorCode);
	}
}
