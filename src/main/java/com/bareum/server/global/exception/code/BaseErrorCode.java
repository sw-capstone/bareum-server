package com.bareum.server.global.exception.code;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

public interface BaseErrorCode {

	HttpStatus getHttpStatus();

	String getCode();

	String getMessage();

	default Level getLogLevel() {
		return Level.INFO;
	}
}
