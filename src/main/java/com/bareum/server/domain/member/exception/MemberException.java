package com.bareum.server.domain.member.exception;

import com.bareum.server.global.exception.BusinessException;
import com.bareum.server.global.exception.Domain;

public class MemberException extends BusinessException {

	public MemberException(MemberErrorCode errorCode) {
		super(Domain.MEMBER, errorCode);
	}
}
