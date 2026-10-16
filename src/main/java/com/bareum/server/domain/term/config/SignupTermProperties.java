package com.bareum.server.domain.term.config;

import com.bareum.server.domain.term.enums.TermCode;
import com.bareum.server.domain.term.exception.TermErrorCode;
import com.bareum.server.domain.term.exception.TermException;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.signup-terms")
public record SignupTermProperties(Map<TermCode, String> versions) {
    public SignupTermProperties {
        versions = versions == null ? Map.of() : Map.copyOf(versions);
    }

    public String versionFor(TermCode code) {
        String version = versions.get(code);
        if (version == null || version.isBlank() || version.length() > 30) {
            throw new TermException(TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED);
        }
        return version;
    }
}
