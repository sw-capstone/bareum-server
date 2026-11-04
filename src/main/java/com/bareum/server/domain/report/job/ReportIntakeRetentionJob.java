package com.bareum.server.domain.report.job;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;

import com.bareum.server.domain.report.service.command.ReportIntakeCommandService;

/** 설정한 실행 간격마다 보관 기한이 지난 접수 요청 기록의 정리를 실행한다. */
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

	@Scheduled(fixedDelayString = "${report.intake.retention-interval-ms:3600000}",
		initialDelayString = "${report.intake.retention-interval-ms:3600000}")
	public int purgeExpiredRequests() {
		return commands.purgeExpiredRequests(clock.instant());
	}
}
