package com.bareum.server.domain.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/** 접수 복구와 원본 삭제, 멱등 기록 정리의 실행 주기와 배치 크기를 환경설정으로 관리한다. */
@Validated
@ConfigurationProperties("report.intake")
public record ReportIntakeProperties(
	@DefaultValue("300000") @Min(1) long recoveryIntervalMs,
	@DefaultValue("600000") @Min(1) long sourceCleanupIntervalMs,
	@DefaultValue("3600000") @Min(1) long retentionIntervalMs,
	@DefaultValue("100") @Min(1) int maintenanceBatchSize,
	@DefaultValue("1000") @Min(1) int retentionBatchSize
) {
}
