package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.bareum.server.domain.report.job.ReportIntakeMaintenanceJob;
import com.bareum.server.domain.report.job.ReportIntakeRetentionJob;
import com.bareum.server.domain.report.service.ReportIntakeMaintenanceService;
import com.bareum.server.domain.report.service.command.ReportIntakeCommandService;
import com.bareum.server.global.config.SchedulingConfig;
import com.bareum.server.global.config.TimeConfig;

/** 보고서 예약 작업의 공통 활성화와 작업별 주기, 스케줄러 분리를 검증한다. */
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
			context.registerBean(ReportIntakeCommandService.class, () -> mock(ReportIntakeCommandService.class));
			registerScheduling(context);
			try {
				context.refresh();
				assertTrue(entered.await(3, TimeUnit.SECONDS));
				assertEquals(3, context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
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
			context.registerBean(ReportIntakeCommandService.class, () -> mock(ReportIntakeCommandService.class));
			registerScheduling(context);
			context.refresh();
			assertTrue(cleanupStarted.await(3, TimeUnit.SECONDS));
			assertTrue(recoveryRuns.await(3, TimeUnit.SECONDS));
		}
		verify(service, atLeast(2)).recoverInterruptedRequests();
		verify(service, times(1)).deleteSources();
	}

	/** 보고서 예약 작업을 꺼도 기본 스케줄러의 다른 작업은 계속 실행하는지 검증한다. */
	@Test
	void disablingReportJobsKeepsOtherScheduledWorkEnabled() throws Exception {
		var unrelatedRun = new CountDownLatch(1);
		try (var context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment()
					.getPropertySources()
					.addFirst(
							new MapPropertySource("disabled-report-jobs",
									Map.of("report.intake.jobs.enabled", "false")));
			context.registerBean(UnrelatedJob.class, () -> new UnrelatedJob(unrelatedRun));
			registerScheduling(context);
			context.refresh();
			assertFalse(context.containsBean("reportIntakeScheduler"));
			assertTrue(context.getBeansOfType(ReportIntakeMaintenanceJob.class).isEmpty());
			assertTrue(context.getBeansOfType(ReportIntakeRetentionJob.class).isEmpty());
			assertTrue(unrelatedRun.await(3, TimeUnit.SECONDS));
		}
	}

	/** 보관 기록 정리가 설정한 주기로 기본 스케줄러에서 실행되는지 검증한다. */
	@Test
	void retentionUsesConfiguredIntervalAndDefaultScheduler() throws Exception {
		var retentionRuns = new CountDownLatch(2);
		var commands = mock(ReportIntakeCommandService.class);
		doAnswer(invocation -> {
			assertTrue(Thread.currentThread().getName().startsWith("test-default-scheduler-"));
			retentionRuns.countDown();
			return 0;
		}).when(commands).purgeExpiredRequests(any(Instant.class));
		try (var context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment()
					.getPropertySources()
					.addFirst(
							new MapPropertySource("retention-interval",
									Map.of("report.intake.retention-interval-ms", "20")));
			context.registerBean(ReportIntakeMaintenanceService.class,
					() -> mock(ReportIntakeMaintenanceService.class));
			context.registerBean(ReportIntakeCommandService.class, () -> commands);
			registerScheduling(context);
			context.refresh();
			assertTrue(retentionRuns.await(3, TimeUnit.SECONDS));
		}
		verify(commands, atLeast(2)).purgeExpiredRequests(any(Instant.class));
	}

	private void registerScheduling(AnnotationConfigApplicationContext context) {
		context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class, () -> {
			var scheduler = new ThreadPoolTaskScheduler();
			scheduler.setThreadNamePrefix("test-default-scheduler-");
			return scheduler;
		});
		context.register(TimeConfig.class, SchedulingConfig.class);
	}

	static class UnrelatedJob {
		private final CountDownLatch run;

		UnrelatedJob(CountDownLatch run) {
			this.run = run;
		}

		@Scheduled(fixedDelay = 60_000)
		public void poll() {
			run.countDown();
		}
	}
}
