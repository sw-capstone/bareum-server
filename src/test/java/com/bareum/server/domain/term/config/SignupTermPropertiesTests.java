package com.bareum.server.domain.term.config;

import static org.junit.jupiter.api.Assertions.*;

import com.bareum.server.domain.term.enums.TermCode;
import com.bareum.server.domain.term.exception.TermErrorCode;
import com.bareum.server.domain.term.exception.TermException;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SignupTermPropertiesTests {
    @Test
    void missingPinnedVersionBlocksSignupConfiguration() {
        var properties = new SignupTermProperties(null);
        assertSame(TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED,
                assertThrows(TermException.class, () -> properties.versionFor(TermCode.TERMS_OF_SERVICE)).getBaseErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "0123456789012345678901234567890xx"})
    void invalidPinnedVersionBlocksSignupConfiguration(String version) {
        var properties = new SignupTermProperties(Map.of(TermCode.TERMS_OF_SERVICE, version));
        assertThrows(TermException.class, () -> properties.versionFor(TermCode.TERMS_OF_SERVICE));
    }

    @Test
    void boundVersionsAreAnImmutableSnapshot() {
        var versions = new EnumMap<TermCode, String>(TermCode.class);
        versions.put(TermCode.TERMS_OF_SERVICE, "fixed-v1");
        var properties = new SignupTermProperties(versions);
        versions.put(TermCode.TERMS_OF_SERVICE, "changed");
        assertEquals("fixed-v1", properties.versionFor(TermCode.TERMS_OF_SERVICE));
        assertThrows(UnsupportedOperationException.class,
                () -> properties.versions().put(TermCode.TERMS_OF_SERVICE, "changed"));
    }
}
