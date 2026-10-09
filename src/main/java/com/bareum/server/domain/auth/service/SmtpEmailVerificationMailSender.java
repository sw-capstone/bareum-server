package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class SmtpEmailVerificationMailSender
        implements EmailVerificationMailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String from;

    public SmtpEmailVerificationMailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${auth.email-verification.mail-from:}") String from
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.from = from;
    }

    @Override
    public void sendVerificationCode(
            String email,
            String code,
            VerificationPurpose purpose
    ) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender == null || !StringUtils.hasText(from)) {
            log.error("이메일 인증 메일 발송 설정이 누락되었습니다.");

            throw new AuthException(
                    AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED
            );
        }

        String action = switch (purpose) {
            case SIGNUP -> "회원가입";
            case PASSWORD_RESET -> "비밀번호 재설정";
        };

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("[바름] " + action + " 이메일 인증");
        message.setText("""
                바름 %s을 위한 인증번호입니다.

                인증번호: %s

                화면에 표시된 유효시간 내에 인증번호를 입력해 주세요.
                직접 요청하지 않았다면 이 메일을 무시해 주세요.
                """.formatted(action, code));

        try {
            mailSender.send(message);
        } catch (MailException exception) {
            log.error(
                    "이메일 인증 메일 발송 실패. 예외 종류: {}",
                    exception.getClass().getSimpleName()
            );

            throw new AuthException(
                    AuthErrorCode.EMAIL_SEND_FAILED
            );
        }
    }
}
