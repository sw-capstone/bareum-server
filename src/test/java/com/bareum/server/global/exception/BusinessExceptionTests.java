package com.bareum.server.global.exception;

import com.bareum.server.global.exception.code.BaseErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTests {

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n"})
	void missingCustomMessageUsesDefaultAndPreservesCause(String message) {
		IllegalStateException cause = new IllegalStateException("private-cause");
		TestBusinessException exception = new TestBusinessException(TestErrorCode.NOT_FOUND, message, cause);
		assertThat(exception.getMessage()).isEqualTo(TestErrorCode.NOT_FOUND.getMessage());
		assertThat(exception.getCause()).isSameAs(cause);
		assertThat(exception.getDomain()).isEqualTo(Domain.DOCUMENT);
	}

	static class TestBusinessException extends BusinessException {

		TestBusinessException(TestErrorCode errorCode, String message, Throwable cause) {
			super(Domain.DOCUMENT, errorCode, message, cause);
		}
	}

	enum TestErrorCode implements BaseErrorCode {
		NOT_FOUND;

		@Override
		public HttpStatus getHttpStatus() {
			return HttpStatus.NOT_FOUND;
		}

		@Override
		public String getCode() {
			return "DOCUMENT-0001";
		}

		@Override
		public String getMessage() {
			return "대상을 찾을 수 없습니다.";
		}
	}
}
