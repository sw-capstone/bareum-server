package com.bareum.server.domain.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/** 접수 동시 처리 수와 복구, 원본 삭제 및 멱등 기록 정리의 실행 설정을 관리한다. */
@Validated
@ConfigurationProperties("report.intake")
public record ReportIntakeProperties(
	@DefaultValue("300000") @Min(1) long recoveryIntervalMs,
	@DefaultValue("600000") @Min(1) long sourceCleanupIntervalMs,
	@DefaultValue("3600000") @Min(1) long retentionIntervalMs,
	@DefaultValue("100") @Min(1) int maintenanceBatchSize,
	@DefaultValue("1000") @Min(1) int retentionBatchSize,
	@DefaultValue("1") @Min(1) int maxConcurrentRequests
) {
}
