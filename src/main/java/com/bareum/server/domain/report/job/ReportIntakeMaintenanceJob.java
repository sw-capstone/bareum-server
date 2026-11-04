package com.bareum.server.domain.report.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bareum.server.domain.report.intake.ReportIntakePolicy;
import com.bareum.server.domain.report.service.ReportIntakeMaintenanceService;

import lombok.RequiredArgsConstructor;

/** 서버 시작 직후부터 매 실행 완료 후 1분마다 중단 요청 복구와 원본 삭제를 수행한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "report.intake.maintenance-enabled", havingValue = "true", matchIfMissing = true)
public class ReportIntakeMaintenanceJob {
	private final ReportIntakeMaintenanceService maintenance;

	@Scheduled(fixedDelay = ReportIntakePolicy.MAINTENANCE_INTERVAL_MS,
		initialDelay = 0,
		scheduler = "reportIntakeScheduler")
	public void recoverInterruptedRequests() {
		maintenance.recoverInterruptedRequests();
	}

	@Scheduled(fixedDelay = ReportIntakePolicy.MAINTENANCE_INTERVAL_MS,
		initialDelay = 0,
		scheduler = "reportIntakeScheduler")
	public void deleteSources() {
		maintenance.deleteSources();
	}
}
