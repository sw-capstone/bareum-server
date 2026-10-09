package com.bareum.server.domain.auth.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmailVerificationTimeConfig {

    @Bean
    public Clock emailVerificationClock() {
        return Clock.systemUTC();
    }
}
