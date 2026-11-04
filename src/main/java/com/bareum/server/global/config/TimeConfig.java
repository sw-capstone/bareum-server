package com.bareum.server.global.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 서버 전체에서 사용할 UTC 시간 공급자를 등록한다. */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
