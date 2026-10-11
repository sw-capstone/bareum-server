package com.bareum.server.domain.auth.service;

// 검증된 프로필의 내부 전달값이며 로그인 토큰이나 가입 완료 증명이 아닙니다.
public record GoogleIdentity(String subject, String email, String name, String hostedDomain) {

    @Override
    public String toString() {
        return "GoogleIdentity[redacted]";
    }
}
