package com.bareum.server.domain.report.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bareum.server.domain.report.service.ReportIntakeMaintenanceService;

import lombok.RequiredArgsConstructor;

/** 서버 시작 직후부터 설정한 실행 간격마다 중단 요청 복구와 원본 삭제를 수행한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "report.intake.maintenance-enabled", havingValue = "true", matchIfMissing = true)
public class ReportIntakeMaintenanceJob {
	private final ReportIntakeMaintenanceService maintenance;

	@Scheduled(fixedDelayString = "${report.intake.recovery-interval-ms:300000}",
		initialDelay = 0,
		scheduler = "reportIntakeScheduler")
	public void recoverInterruptedRequests() {
		maintenance.recoverInterruptedRequests();
	}

	@Scheduled(fixedDelayString = "${report.intake.source-cleanup-interval-ms:600000}",
		initialDelay = 0,
		scheduler = "reportIntakeScheduler")
	public void deleteSources() {
		maintenance.deleteSources();
	}
}
