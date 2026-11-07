package com.bareum.server.domain.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 이메일 인증과 발송 대기열의 설정값을 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
	EmailVerificationProperties.class, EmailOutboxProperties.class
})
public class EmailVerificationConfig {
}
