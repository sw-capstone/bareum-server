package com.bareum.server.domain.auth.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;

import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationConfirmResponse;
import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface EmailVerificationControllerDocs {

	@Operation(summary = "이메일 인증 코드 발송", description = """
			SIGNUP은 이메일·구글 가입 계정의 중복을 확인합니다.
			PASSWORD_RESET은 이메일·비밀번호로 로그인 가능한 계정만 요청할 수 있습니다.
			인증 요청과 암호화한 발송 작업을 함께 커밋하고 202 접수를 반환합니다.
			접수 응답은 requestId·status=PENDING·expiresIn=0입니다. Location의 상태 조회 주소로 발송 결과를 확인합니다.
			SMTP 수락 후 회원가입 인증번호의 5분 유효기간을 시작하며, SENT 상태의 expiresIn으로 타이머를 시작합니다.
			비동기 응답 및 상태 조회 계약은 프론트와 협의가 필요한 구현안입니다.
			인증번호 원문은 응답에 포함하지 않으며, 이 요청으로 회원 계정을 생성하지 않습니다.
			발송 제한 또는 해당 목적의 필수 설정이 누락되면 발송하지 않습니다.
			""")
	@ApiResponses({
		@ApiResponse(responseCode = "202",
			description = "인증 메일 발송 요청 접수 (발송 완료 아님)",
			content = @Content(mediaType = "application/json",
				schema = @Schema(implementation = EmailVerificationDeliveryResponse.class))),
		@ApiResponse(responseCode = "400",
			description = "입력값 검증 실패(COMMON-0002), 본문 누락·잘못된 JSON 또는 인증 목적(COMMON-0003), "
					+ "비밀번호 재설정 불가 계정(AUTH-0003)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail"),
				examples = {
					@ExampleObject(ref = "#/components/examples/ValidationFailed"),
					@ExampleObject(ref = "#/components/examples/InvalidRequestBody")
				})),
		@ApiResponse(responseCode = "404",
			description = "비밀번호 재설정을 요청한 이메일이 미가입 상태(AUTH-0002)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(responseCode = "409",
			description = "회원가입을 요청한 이메일이 이미 가입된 상태(AUTH-0001)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(responseCode = "429",
			description = "인증 메일 발송 제한 초과 또는 같은 이메일 발송 대기 중(AUTH-0004)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(responseCode = "500",
			description = "접수 저장 실패 등 서버 오류(COMMON-0001). 접수 이후 발송 실패는 상태 조회로 확인",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(responseCode = "503",
			description = "이메일 인증 정책·SMTP·Outbox 암호화 키 설정 누락(AUTH-0006)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	})
	ResponseEntity<EmailVerificationDeliveryResponse> send(EmailVerificationRequest request);

	@Operation(summary = "인증 메일 발송 상태 조회", description = """
			접수 응답의 requestId로 조회합니다. PENDING 동안 타이머를 시작하지 않습니다.
			SENT는 SMTP 서버 수락이며 수신함 도착을 보장하지 않습니다.
			SENT의 expiresIn으로 인증번호 타이머를 시작하고, FAILED 또는 CANCELLED는 발송 실패·무효화를 안내합니다.
			요청 ID는 로그·분석 도구에 노출하지 않습니다. 응답은 Cache-Control: no-store를 사용합니다.
			상태 조회 계약은 프론트 연동 전 협의가 필요한 구현안입니다.
			""")
	@ApiResponse(responseCode = "200",
		description = "발송 상태",
		content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = EmailVerificationDeliveryResponse.class)))
	@ApiResponse(responseCode = "404", description = "요청 ID 없음. 응답 본문 없음", content = @Content)
	@ApiResponse(responseCode = "400",
		description = "잘못된 요청 ID 형식",
		content = @Content(mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	ResponseEntity<EmailVerificationDeliveryResponse> status(UUID requestId);

	@Operation(summary = "이메일 인증 코드 확인", description = """
			이메일·목적의 최신 요청만 확인합니다. 성공하면 인증 완료 증명을 반환하며 회원 생성이나 비밀번호 변경은 수행하지 않습니다.
			가입용 증명은 10분간 유효합니다. 최종 사용 시 이메일·목적·유효기간·미사용 상태를 다시 검증해야 합니다.
			실패 횟수 제한 및 목적별 증명 유효기간 설정이 없으면 확인을 차단합니다.
			이미 완료된 요청에는 새 증명을 발급하지 않습니다. 요청·응답 및 오류 매핑은 팀 검토가 필요한 초안입니다.
			""")
	@ApiResponses({
		@ApiResponse(responseCode = "200",
			description = "인증 완료 증명 발급",
			content = @Content(mediaType = "application/json",
				schema = @Schema(implementation = EmailVerificationConfirmResponse.class))),
		@ApiResponse(responseCode = "400",
			description = "입력 오류(COMMON-0002/0003), 유효하지 않은 요청(AUTH-0007), 만료(AUTH-0008), 번호 불일치(AUTH-0009)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail"))),
		@ApiResponse(responseCode = "409",
			description = "이미 인증 완료(AUTH-0011)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(responseCode = "429",
			description = "실패 횟수 초과(AUTH-0010)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(responseCode = "503",
			description = "필수 정책 설정 누락(AUTH-0006)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(responseCode = "500",
			description = "서버 오류(COMMON-0001)",
			content = @Content(mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	})
	ResponseEntity<EmailVerificationConfirmResponse> confirm(EmailVerificationConfirmRequest request);

	@Operation(
		summary = "이메일 인증 코드 재발송",
		description = """
				이메일·목적에 해당하는 최신 미인증 요청의 번호를 교체합니다.
				만료된 번호도 다시 발송할 수 있으며 검증 실패 횟수는 초기화하지 않습니다.
				인증 요청과 암호화한 발송 작업을 함께 커밋하고 별도 worker가 메일을 발송합니다.
				회원가입 인증번호 유효기간은 SMTP 수락 시점부터 5분입니다.
				202 접수 응답은 requestId·PENDING·expiresIn=0이며 Location에서 상태를 조회합니다.
				대기 중에는 타이머를 시작하지 않고 SENT의 남은 시간(초)을 사용합니다.
				최종 실패·취소는 FAILED·CANCELLED로 안내하며 인증번호 원문은 반환하지 않습니다.
				최초 발송과 같은 발송 제한을 적용합니다. 필수 설정 누락 시 발송하지 않습니다.
				완료 증명 분실 복구는 #54의 후속 변경으로 분리하며, 현재는 완료 증명이 있는 요청을 보존하고 503을 반환합니다.
				요청·응답·오류 매핑과 인증 요청 식별 방식은 팀 검토가 필요한 구현 초안입니다.
				""")
	@ApiResponses({
		@ApiResponse(
			responseCode = "202",
			description = "인증 메일 재발송 접수. Location에서 발송 상태 조회",
			content = @Content(
				mediaType = "application/json",
				schema = @Schema(
					implementation = EmailVerificationDeliveryResponse.class))),
		@ApiResponse(
			responseCode = "400",
			description = "입력 검증 실패(COMMON-0002), 잘못된 본문(COMMON-0003), "
					+ "재설정 불가 계정(AUTH-0003), 인증 요청 없음·미발송·이미 사용된 요청(AUTH-0007)",
			content = @Content(
				mediaType = "application/problem+json",
				schema = @Schema(
					ref = "#/components/schemas/BadRequestProblemDetail"),
				examples = {
					@ExampleObject(ref = "#/components/examples/ValidationFailed"),
					@ExampleObject(ref = "#/components/examples/InvalidRequestBody")
				})),
		@ApiResponse(
			responseCode = "404",
			description = "비밀번호 재설정 이메일이 미가입 상태(AUTH-0002)",
			content = @Content(
				mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(
			responseCode = "409",
			description = "회원가입 이메일이 이미 가입된 상태(AUTH-0001)",
			content = @Content(
				mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(
			responseCode = "429",
			description = "인증 메일 발송 제한 초과(AUTH-0004)",
			content = @Content(
				mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(
			responseCode = "500",
			description = "동일 번호 생성·접수 저장 실패(AUTH-0005) 또는 서버 오류",
			content = @Content(
				mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
		@ApiResponse(
			responseCode = "503",
			description = "필수 설정 누락 또는 인증 완료 후 복구 경로 미적용(AUTH-0006)",
			content = @Content(
				mediaType = "application/problem+json",
				schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	})
	ResponseEntity<EmailVerificationDeliveryResponse> resend(EmailVerificationRequest request);
}
