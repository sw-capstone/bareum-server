package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.domain.report.config.ReportIntakeProperties;
import com.bareum.server.domain.report.config.ReportUploadConfig;
import com.bareum.server.domain.report.repository.ReportContentRepository;
import com.bareum.server.domain.report.repository.ReportIntakeRequestRepository;
import com.bareum.server.domain.report.repository.ReportParseAttemptRepository;
import com.bareum.server.domain.report.repository.ReportRepository;
import com.bareum.server.domain.report.service.command.ReportIntakeCommandService;
import com.bareum.server.domain.report.service.query.ReportMaintenanceQueryService;

/** 운영 환경변수의 설정 바인딩과 실제 배치 처리 반영, 잘못된 값의 시작 차단을 검증한다. */
class ReportIntakeConfigurationTests {
	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(ReportUploadConfig.class, ReportMaintenanceQueryService.class,
					ReportIntakeCommandService.class)
			.withBean(Clock.class, Clock::systemUTC)
			.withBean(MemberRepository.class, () -> mock(MemberRepository.class))
			.withBean(ReportRepository.class, () -> mock(ReportRepository.class))
			.withBean(ReportIntakeRequestRepository.class, () -> mock(ReportIntakeRequestRepository.class))
			.withBean(ReportParseAttemptRepository.class, () -> mock(ReportParseAttemptRepository.class))
			.withBean(ReportContentRepository.class, () -> mock(ReportContentRepository.class))
			.withInitializer(ctx -> {
				try {
					new YamlPropertySourceLoader().load("intake-settings", new ClassPathResource("application.yml"))
							.forEach(source -> ctx.getEnvironment().getPropertySources().addLast(source));
				} catch (java.io.IOException exception) {
					throw new IllegalStateException(exception);
				}
			});

	/** 운영 환경변수가 주기 설정과 실제 조회 및 삭제에 전달되는 배치 크기를 바꾸는지 검증한다. */
	@Test
	void deploymentEnvironmentChangesIntervalsAndActualBatchConsumers() {
		Map<String, Object> environment = Map.of(
				"REPORT_INTAKE_RECOVERY_INTERVAL_MS", "1500",
				"REPORT_INTAKE_SOURCE_CLEANUP_INTERVAL_MS", "2500",
				"REPORT_INTAKE_RETENTION_INTERVAL_MS", "3500",
				"REPORT_INTAKE_MAINTENANCE_BATCH_SIZE", "7",
				"REPORT_INTAKE_RETENTION_BATCH_SIZE", "13");
		context.withInitializer(ctx -> ctx.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("intake-systemEnvironment", environment))).run(ctx -> {
					assertNull(ctx.getStartupFailure());
					var settings = ctx.getBean(ReportIntakeProperties.class);
					assertEquals(1500, settings.recoveryIntervalMs());
					assertEquals(2500, settings.sourceCleanupIntervalMs());
					assertEquals(3500, settings.retentionIntervalMs());
					var queries = ctx.getBean(ReportMaintenanceQueryService.class);
					var now = Instant.now();
					queries.expiredRequests(now, 42);
					queries.orphanSources(now, 42);
					queries.reportSources(now, 42);
					ctx.getBean(ReportIntakeCommandService.class).purgeExpiredRequests(now);
					var requests = ctx.getBean(ReportIntakeRequestRepository.class);
					verify(requests).findExpiredProcessingIds(now, 42, 7);
					verify(requests).findOrphanCleanupIds(now, 42, 7);
					verify(requests).deleteExpiredBatch(now, 13);
					verify(ctx.getBean(ReportRepository.class)).findSourceCleanupIds(now, 42, 7);
				});
	}

	/** 주기나 배치 크기에 0 또는 음수를 주입하면 잘못된 설정으로 서버 시작을 차단하는지 검증한다. */
	@ParameterizedTest
	@CsvSource({"REPORT_INTAKE_RECOVERY_INTERVAL_MS,0", "REPORT_INTAKE_RECOVERY_INTERVAL_MS,-1",
		"REPORT_INTAKE_SOURCE_CLEANUP_INTERVAL_MS,0", "REPORT_INTAKE_SOURCE_CLEANUP_INTERVAL_MS,-1",
		"REPORT_INTAKE_RETENTION_INTERVAL_MS,0", "REPORT_INTAKE_RETENTION_INTERVAL_MS,-1",
		"REPORT_INTAKE_MAINTENANCE_BATCH_SIZE,0", "REPORT_INTAKE_MAINTENANCE_BATCH_SIZE,-1",
		"REPORT_INTAKE_RETENTION_BATCH_SIZE,0", "REPORT_INTAKE_RETENTION_BATCH_SIZE,-1"})
	void rejectsInvalidOperationalEnvironment(String variable, String value) {
		context.withInitializer(ctx -> ctx.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("invalid-intake-systemEnvironment",
						Map.of(variable, value))))
				.run(ctx -> assertNotNull(ctx.getStartupFailure()));
	}
}
