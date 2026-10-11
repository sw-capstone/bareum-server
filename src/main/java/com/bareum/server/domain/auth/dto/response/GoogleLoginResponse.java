package com.bareum.server.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoogleLoginResponse(
        @Schema(allowableValues = {"LOGIN", "SIGNUP_REQUIRED"}) String status,
        String accessToken,
        String tokenType,
        Long expiresIn,
        Long sessionExpiresIn,
        @Schema(description = "AUTH-011 전용 가입 증명. 보호 API에 사용할 수 없음") String signupToken,
        Long signupExpiresIn) {
    public static GoogleLoginResponse login(EmailLoginResponse login) {
        return new GoogleLoginResponse(
                "LOGIN",
                login.accessToken(),
                login.tokenType(),
                login.expiresIn(),
                login.sessionExpiresIn(),
                null,
                null);
    }

    public static GoogleLoginResponse signup(String token, long expiresIn) {
        return new GoogleLoginResponse("SIGNUP_REQUIRED", null, null, null, null, token, expiresIn);
    }

    @Override
    public String toString() {
        return "GoogleLoginResponse[redacted]";
    }
}
