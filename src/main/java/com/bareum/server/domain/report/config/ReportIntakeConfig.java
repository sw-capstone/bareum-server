package com.bareum.server.domain.report.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 보고서 접수와 원본 보관 기한을 계산할 UTC 시간 공급자를 등록한다. */
@Configuration(proxyBeanMethods = false)
public class ReportIntakeConfig {
	@Bean
	public Clock reportIntakeClock() {
		return Clock.systemUTC();
	}
}
