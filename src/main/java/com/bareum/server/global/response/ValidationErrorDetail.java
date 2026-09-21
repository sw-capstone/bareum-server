package com.bareum.server.global.response;

import org.jspecify.annotations.Nullable;

/**
 * 사용자 입력값의 검증 실패를 안내할 때 사용하는 DTO
 */
public record ValidationErrorDetail(@Nullable String field, String message) {
}
