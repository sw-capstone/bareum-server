package com.bareum.server.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EmailLoginRequest(
	@NotBlank @Email @Size(max = 255) String email,
	@NotNull @Size(min = 1) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password,
	@AssertFalse(message = "로그인 상태 유지 기능은 아직 지원하지 않습니다.") Boolean keepSignedIn
) {
	@Override
	public String toString() {
		return "EmailLoginRequest[redacted]";
	}
}
