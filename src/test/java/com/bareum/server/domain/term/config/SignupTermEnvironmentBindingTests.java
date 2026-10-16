package com.bareum.server.domain.term.config;

import static org.junit.jupiter.api.Assertions.*;

import com.bareum.server.domain.term.enums.TermCode;
import com.bareum.server.domain.term.exception.TermException;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

class SignupTermEnvironmentBindingTests {
    private SignupTermProperties bind(Map<String, Object> variables) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        var binder = new Binder(ConfigurationPropertySources.get(environment),
                new PropertySourcesPlaceholdersResolver(environment));
        return binder.bind("auth.signup-terms", Bindable.of(SignupTermProperties.class)).get();
    }

    @Test
    void approvedVersionsBindToAllFourTermCodes() throws IOException {
        var properties = bind(Map.of(
                "SIGNUP_TERMS_AGE_OVER_14_VERSION", "approved-age",
                "SIGNUP_TERMS_TERMS_OF_SERVICE_VERSION", "approved-service",
                "SIGNUP_TERMS_PRIVACY_POLICY_VERSION", "approved-privacy",
                "SIGNUP_TERMS_MARKETING_CONSENT_VERSION", "approved-marketing"));
        assertEquals("approved-age", properties.versionFor(TermCode.AGE_OVER_14));
        assertEquals("approved-service", properties.versionFor(TermCode.TERMS_OF_SERVICE));
        assertEquals("approved-privacy", properties.versionFor(TermCode.PRIVACY_POLICY));
        assertEquals("approved-marketing", properties.versionFor(TermCode.MARKETING_CONSENT));
    }

    @ParameterizedTest
    @EnumSource(TermCode.class)
    void missingApprovedVersionStillBlocksSignup(TermCode code) throws IOException {
        var properties = bind(Map.of());
        assertThrows(TermException.class, () -> properties.versionFor(code));
    }
}
