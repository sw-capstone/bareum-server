package com.bareum.server.domain.report.body;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 검증을 통과한 공통 본문을 보관하는 클래스.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ValidatedReportBody {
	private static final BodyValidator VALIDATOR = new BodyValidator();

	private final ReportBody body;

	public static ValidatedReportBody from(ReportBody body) {
		VALIDATOR.validate(body);
		return new ValidatedReportBody(body);
	}
}
