package com.bareum.server.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("auth.email-verification.recovery")
public record EmailVerificationRecoveryProperties(@DefaultValue("900") long attemptCooldownSeconds) {
    public EmailVerificationRecoveryProperties {
        if (attemptCooldownSeconds < 1 || attemptCooldownSeconds > 86_400) {
            throw new IllegalArgumentException("Verification cooldown must be between 1 and 86400 seconds");
        }
    }
}
