package com.bareum.server.domain.report.job;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bareum.server.domain.report.intake.ReportIntakePolicy;
import com.bareum.server.domain.report.service.command.ReportIntakeCommandService;

/** 매시간 보관 기한이 지난 접수 요청 기록의 정리를 실행하는 스케줄러. */
@Component
public class ReportIntakeRetentionJob {
	private final ReportIntakeCommandService commands;
	private final Clock clock;

	public ReportIntakeRetentionJob(
		ReportIntakeCommandService commands,
		Clock clock
	) {
		this.commands = commands;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = ReportIntakePolicy.IDEMPOTENCY_CLEANUP_INTERVAL_MS,
		initialDelay = ReportIntakePolicy.IDEMPOTENCY_CLEANUP_INTERVAL_MS)
	public int purgeExpiredRequests() {
		return commands.purgeExpiredRequests(clock.instant());
	}
}
