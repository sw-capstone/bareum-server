package com.bareum.server.domain.report.intake;

import java.time.Duration;

/** PDF 접수의 파일 제한과 보관 기간, 재시도 한도를 정의한다. */
public final class ReportIntakePolicy {

	public static final long MAX_FILE_SIZE_BYTES = 20_000_000;
	public static final int MAX_PAGE_COUNT = 60;
	public static final Duration SOURCE_RETENTION = Duration.ofDays(30);
	public static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
	public static final int MAX_ADDITIONAL_PARSE_ATTEMPTS = 1;
	public static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofSeconds(340);

	private ReportIntakePolicy() {
	}
}
