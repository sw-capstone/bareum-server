package com.bareum.server.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.bareum.server.domain.report.job.ReportIntakeMaintenanceJob;
import com.bareum.server.domain.report.job.ReportIntakeRetentionJob;

/** 예약 작업을 활성화하고 보고서 접수 Job과 전용 스케줄러의 등록을 관리한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "report.intake.jobs.enabled", havingValue = "true", matchIfMissing = true)
	@Import({
		ReportIntakeMaintenanceJob.class, ReportIntakeRetentionJob.class
	})
	static class ReportIntakeSchedulingConfig {
		/** 원본 삭제가 복구나 기본 스케줄러의 다른 예약 작업을 막지 않도록 분리한다. */
		@Bean(defaultCandidate = false)
		public ThreadPoolTaskScheduler reportIntakeScheduler() {
			var scheduler = new ThreadPoolTaskScheduler();
			scheduler.setPoolSize(2);
			scheduler.setThreadNamePrefix("report-intake-maintenance-");
			return scheduler;
		}
	}
}
