package com.bareum.server.domain.auth.controller;

import org.springframework.http.ResponseEntity;

import com.bareum.server.domain.auth.dto.request.EmailSignupRequest;
import com.bareum.server.domain.auth.dto.response.EmailSignupResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Auth", description = "인증 API")
public interface EmailSignupControllerDocs {

	@Operation(summary = "이메일 회원가입",
		description = "AUTH-001 구현 초안. SIGNUP 인증 완료 증명과 필수 약관 동의를 확인하고 "
				+ "회원·동의 이력 저장과 증명 사용을 함께 처리합니다. 비밀번호는 최소 12자로 영문·숫자·!@#$%& 각각 포함, "
				+ "공백 금지입니다. agreements에 age·service·privacy·marketing을 모두 true/false로 전달합니다. "
				+ "age·service·privacy는 필수, marketing은 false도 가입 가능합니다. 가입 후 자동 로그인하지 않습니다. "
				+ "약관 원문은 프론트에서 표시하며 서버가 DB의 항목별 약관 ID·버전을 연결해 동의 이력을 저장합니다. "
				+ "별도 약관 버전 환경변수나 원문 확정 여부를 검사하지 않습니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "201",
			description = "가입 완료. 회원 ID만 반환",
			content = @Content(mediaType = "application/json",
				schema = @Schema(implementation = EmailSignupResponse.class))),
		@ApiResponse(responseCode = "400",
			description = "입력 오류, 인증 증명 무효·만료(AUTH-0007/0013), 비밀번호 조건 위반(AUTH-0012), "
					+ "약관 정보 오류·필수 미동의(TERM-0002/0003)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail"))),
		@ApiResponse(responseCode = "409",
			description = "이미 가입된 이메일(AUTH-0001)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(responseCode = "500",
			description = "서버 오류 또는 약관 데이터 무결성 오류(TERM-0004)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	})
	ResponseEntity<EmailSignupResponse> signup(EmailSignupRequest request);
}
