package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.bareum.server.domain.report.config.ReportUploadConfig;
import com.bareum.server.domain.report.job.ReportIntakeMaintenanceJob;
import com.bareum.server.domain.report.service.ReportIntakeMaintenanceService;
import com.bareum.server.global.config.TimeConfig;

/** 시작 직후 두 정리 작업이 기본 스케줄러와 별도로 서로 막지 않고 실행되는지 검증한다. */
class ReportIntakeMaintenanceJobTests {
	/** 두 예약 작업이 시작 직후 전용 스케줄러에서 서로 막지 않고 실행되는지 검증한다. */
	@Test
	void startsBothJobsImmediatelyEvenWhenOneIsStillRunning() throws Exception {
		var entered = new CountDownLatch(2);
		var release = new CountDownLatch(1);
		var service = mock(ReportIntakeMaintenanceService.class);
		org.mockito.stubbing.Answer<Void> hold = invocation -> {
			assertTrue(Thread.currentThread().getName().startsWith("report-intake-maintenance-"));
			entered.countDown();
			assertTrue(release.await(5, TimeUnit.SECONDS));
			return null;
		};
		doAnswer(hold).when(service).recoverInterruptedRequests();
		doAnswer(hold).when(service).deleteSources();
		try (var context = new AnnotationConfigApplicationContext()) {
			context.registerBean(ReportIntakeMaintenanceService.class, () -> service);
			context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new);
			context.register(TimeConfig.class, ReportUploadConfig.class, ReportIntakeMaintenanceJob.class);
			try {
				context.refresh();
				assertTrue(entered.await(3, TimeUnit.SECONDS));
			} finally {
				release.countDown();
			}
		}
		verify(service).recoverInterruptedRequests();
		verify(service).deleteSources();
	}

	/** 설정한 복구 주기가 실제 재실행에 반영되고 원본 정리 주기는 별도로 적용되는지 검증한다. */
	@Test
	void configuredIntervalsControlEachJobIndependently() throws Exception {
		var recoveryRuns = new CountDownLatch(2);
		var cleanupStarted = new CountDownLatch(1);
		var service = mock(ReportIntakeMaintenanceService.class);
		doAnswer(i -> {
			recoveryRuns.countDown();
			return null;
		}).when(service).recoverInterruptedRequests();
		doAnswer(i -> {
			cleanupStarted.countDown();
			return null;
		}).when(service).deleteSources();
		try (var context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment()
					.getPropertySources()
					.addFirst(new MapPropertySource("job-intervals", Map.of(
							"report.intake.recovery-interval-ms", "20", "report.intake.source-cleanup-interval-ms",
							"300000")));
			context.registerBean(ReportIntakeMaintenanceService.class, () -> service);
			context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new);
			context.register(TimeConfig.class, ReportUploadConfig.class, ReportIntakeMaintenanceJob.class);
			context.refresh();
			assertTrue(cleanupStarted.await(3, TimeUnit.SECONDS));
			assertTrue(recoveryRuns.await(3, TimeUnit.SECONDS));
		}
		verify(service, atLeast(2)).recoverInterruptedRequests();
		verify(service, times(1)).deleteSources();
	}
}
