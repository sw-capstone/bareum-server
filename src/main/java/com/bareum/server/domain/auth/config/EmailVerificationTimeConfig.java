package com.bareum.server.domain.auth.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmailVerificationProperties.class)
public class EmailVerificationTimeConfig {

    @Bean
    public Clock emailVerificationClock() {
        return Clock.systemUTC();
    }
}
