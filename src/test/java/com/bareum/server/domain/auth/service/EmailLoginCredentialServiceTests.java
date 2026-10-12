package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.repository.MemberRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

class EmailLoginCredentialServiceTests {

    private static final String EMAIL = "user@example.invalid";
    private static final String STORED_HASH = "stored-test-hash";
    private static final String DUMMY_HASH = "dummy-test-hash";
    private final MemberRepository members = mock(MemberRepository.class);
    private final PasswordHashService hashes = mock(PasswordHashService.class);
    private EmailLoginCredentialService service;

    @BeforeEach
    void setUp() {
        when(hashes.hash(anyString())).thenReturn(DUMMY_HASH);
        service = new EmailLoginCredentialService(members, hashes);
        clearInvocations(hashes);
    }

    private Member local() {
        Member member = Member.createLocal(EMAIL, "사용자", STORED_HASH);
        ReflectionTestUtils.setField(member, "id", 42L);
        when(members.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(member));
        return member;
    }

    private void expect(AuthErrorCode error, String email, String password) {
        AuthException exception =
                assertThrows(AuthException.class, () -> service.validateMember(email, password));
        assertSame(error, exception.getBaseErrorCode());
        assertFalse(exception.getMessage().contains(EMAIL));
    }

    @Test
    void validLocalCredentialsPreservePasswordInputAndBindTheVerifiedHash() {
        local();
        String password = String.join(" ", "", "legacy", "input", "with", "spaces", "");
        when(hashes.matches(password, STORED_HASH)).thenReturn(true);
        var validated = service.validateMember("USER@EXAMPLE.INVALID", password);
        assertEquals(42L, validated.memberId());
        assertEquals(STORED_HASH, validated.passwordHash());
        assertFalse(validated.toString().contains(STORED_HASH));
        verify(hashes).matches(password, STORED_HASH);
        verify(hashes, never()).hash(anyString());
        verify(members, never()).save(any());
    }

    @Test
    void missingEmailPerformsDummyComparisonAndUsesSameErrorAsWrongPassword() {
        when(members.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        expect(AuthErrorCode.INVALID_LOGIN_CREDENTIALS, EMAIL, "wrong-input");
        verify(hashes).matches("wrong-input", DUMMY_HASH);
        local();
        expect(AuthErrorCode.INVALID_LOGIN_CREDENTIALS, EMAIL, "wrong-input");
        verify(hashes).matches("wrong-input", STORED_HASH);
    }

    @Test
    void activeGoogleAccountCannotUseEmailPasswordLogin() {
        when(members.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(Member.createGoogle(EMAIL, "사용자")));
        expect(AuthErrorCode.GOOGLE_LOGIN_REQUIRED, EMAIL, "test-input");
        verify(hashes).matches("test-input", DUMMY_HASH);
        verify(hashes, never()).matches("test-input", null);
    }

    @ParameterizedTest
    @EnumSource(
            value = MemberStatus.class,
            names = {"SUSPENDED", "DELETED"})
    void restrictedLocalStateIsRevealedOnlyAfterCorrectPassword(MemberStatus status) {
        Member member = local();
        ReflectionTestUtils.setField(member, "status", status);
        expect(AuthErrorCode.INVALID_LOGIN_CREDENTIALS, EMAIL, "wrong-input");
        when(hashes.matches("correct-input", STORED_HASH)).thenReturn(true);
        expect(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED, EMAIL, "correct-input");
    }

    @ParameterizedTest
    @EnumSource(
            value = MemberStatus.class,
            names = {"SUSPENDED", "DELETED"})
    void restrictedGoogleAccountIsNotAccepted(MemberStatus status) {
        Member member = Member.createGoogle(EMAIL, "사용자");
        ReflectionTestUtils.setField(member, "status", status);
        when(members.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(member));
        expect(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED, EMAIL, "test-input");
        verify(hashes).matches("test-input", DUMMY_HASH);
    }

    @Test
    void withdrawalTimestampBlocksEvenAnActiveLocalAccount() {
        Member member = local();
        ReflectionTestUtils.setField(member, "withdrawnAt", Instant.parse("2026-10-09T00:00:00Z"));
        when(hashes.matches("correct-input", STORED_HASH)).thenReturn(true);
        expect(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED, EMAIL, "correct-input");
    }

    @Test
    void withdrawalTimestampBlocksEvenAnActiveGoogleAccount() {
        Member member = Member.createGoogle(EMAIL, "사용자");
        ReflectionTestUtils.setField(member, "withdrawnAt", Instant.parse("2026-10-09T00:00:00Z"));
        when(members.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(member));
        expect(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED, EMAIL, "test-input");
    }

    @Test
    void unknownSignupMethodNeverAuthenticates() {
        Member member = local();
        ReflectionTestUtils.setField(member, "signupMethod", null);
        when(hashes.matches("test-input", STORED_HASH)).thenReturn(true);
        expect(AuthErrorCode.INVALID_LOGIN_CREDENTIALS, EMAIL, "test-input");
    }

    @ParameterizedTest
    @MethodSource("missingInputs")
    void missingInputsNeverQueryMemberOrHash(String email, String password) {
        expect(AuthErrorCode.INVALID_LOGIN_CREDENTIALS, email, password);
        verifyNoInteractions(members, hashes);
    }

    static Stream<Arguments> missingInputs() {
        return Stream.of(
                Arguments.of(null, "input"),
                Arguments.of("", "input"),
                Arguments.of(" ", "input"),
                Arguments.of(EMAIL, null),
                Arguments.of(EMAIL, ""));
    }
}
