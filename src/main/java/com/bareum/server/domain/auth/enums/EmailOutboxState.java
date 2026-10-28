package com.bareum.server.domain.auth.enums;

public enum EmailOutboxState {
	QUEUED,
	SENDING,
	RETRY,
	SENT,
	FAILED,
	CANCELLED
}
