package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmailVerificationAccountValidatorTests {

    private static final String EMAIL = "user@example.com";

    private final MemberRepository memberRepository =
            mock(MemberRepository.class);

    private final EmailVerificationAccountValidator validator =
            new EmailVerificationAccountValidator(memberRepository);

    @Test
    void signupAllowsUnregisteredEmail() {
        when(memberRepository.existsByEmailIgnoreCase(EMAIL))
                .thenReturn(false);

        assertDoesNotThrow(() -> validator.checkCanSend(
                EMAIL,
                VerificationPurpose.SIGNUP
        ));
    }

    @Test
    void signupRejectsRegisteredEmail() {
        when(memberRepository.existsByEmailIgnoreCase(EMAIL))
                .thenReturn(true);

        assertThrows(AuthException.class, () -> validator.checkCanSend(
                EMAIL,
                VerificationPurpose.SIGNUP
        ));
    }

    @Test
    void passwordResetRejectsUnregisteredEmail() {
        when(memberRepository.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.empty());

        assertThrows(AuthException.class, () -> validator.checkCanSend(
                EMAIL,
                VerificationPurpose.PASSWORD_RESET
        ));
    }

    @Test
    void passwordResetRejectsGoogleOnlyAccount() {
        Member member = Member.createGoogle(EMAIL, "테스트 사용자");

        when(memberRepository.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(member));

        assertThrows(AuthException.class, () -> validator.checkCanSend(
                EMAIL,
                VerificationPurpose.PASSWORD_RESET
        ));
    }

    @Test
    void passwordResetAllowsLocalAccount() {
        Member member = Member.createLocal(
                EMAIL,
                "테스트 사용자",
                "test-password-hash"
        );

        when(memberRepository.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(member));

        assertDoesNotThrow(() -> validator.checkCanSend(
                EMAIL,
                VerificationPurpose.PASSWORD_RESET
        ));
    }
}
