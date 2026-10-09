package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.enums.SignupMethod;
import com.bareum.server.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmailVerificationAccountValidator {

    private final MemberRepository memberRepository;

    public void checkCanSend(
            String email,
            VerificationPurpose purpose
    ) {
        switch (purpose) {
            case SIGNUP -> checkSignup(email);
            case PASSWORD_RESET -> checkPasswordReset(email);
        }
    }

    private void checkSignup(String email) {
        if (memberRepository.existsByEmailIgnoreCase(email)) {
            throw new AuthException(
                    AuthErrorCode.EMAIL_ALREADY_REGISTERED
            );
        }
    }

    private void checkPasswordReset(String email) {
        Member member = memberRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AuthException(
                        AuthErrorCode.EMAIL_NOT_REGISTERED
                ));

        if (member.getSignupMethod() != SignupMethod.LOCAL
                || !StringUtils.hasText(member.getPasswordHash())) {
            throw new AuthException(
                    AuthErrorCode.PASSWORD_RESET_NOT_ALLOWED
            );
        }
    }
}
