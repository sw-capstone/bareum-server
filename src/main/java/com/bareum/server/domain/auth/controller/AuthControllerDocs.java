package com.bareum.server.domain.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;

import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.dto.request.GoogleLoginRequest;
import com.bareum.server.domain.auth.dto.response.CsrfTokenResponse;
import com.bareum.server.domain.auth.dto.response.CurrentUserResponse;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;
import com.bareum.server.domain.auth.dto.response.GoogleLoginResponse;
import com.bareum.server.global.security.LoginPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface AuthControllerDocs {
	@Operation(
		summary = "이메일 로그인",
		description = """
				이메일 대소문자를 구분하지 않고 자격증명을 확인합니다. 비밀번호는 변형하지 않습니다.
				Access Token은 Authorization: Bearer로 사용하며 기본 900초입니다.
				Refresh Token은 bareum_refresh HttpOnly 쿠키로만 전달하며 기본 28800초입니다.
				JWT Access·Refresh를 발급하며, 재로그인은 이전 Refresh를 교체하고 이전 Access는 원래 만료까지 유지합니다. keepSignedIn=true는 지원하지 않습니다.
				토큰 갱신은 POST /api/v1/auth/token/renew로 연결합니다. 로그아웃 API는 별도 구현 범위입니다.
				""")
	@ApiResponse(
		responseCode = "200",
		description = "토큰과 갱신 쿠키 발급",
		content = @Content(
			mediaType = "application/json",
			schema = @Schema(implementation = EmailLoginResponse.class)))
	@ApiResponse(
		responseCode = "400",
		description = "입력 검증 오류",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail")))
	@ApiResponse(
		responseCode = "401",
		description = "잘못된 자격증명",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "403",
		description = "Google 로그인 필요 또는 제한 계정",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "429",
		description = "로그인 요청 제한",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "503",
		description = "로그인 인증 설정을 사용할 수 없음",
		content = @Content(mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "500",
		description = "저장 실패",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	ResponseEntity<EmailLoginResponse> login(EmailLoginRequest request);

	@Operation(
		summary = "현재 사용자 조회",
		description = "Authorization: Bearer 인증으로 이름·이메일을 조회합니다. 쿠키만으로 인증하지 않습니다.",
		security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponse(
		responseCode = "200",
		description = "현재 사용자",
		content = @Content(
			mediaType = "application/json",
			schema = @Schema(implementation = CurrentUserResponse.class)))
	@ApiResponse(
		responseCode = "401",
		description = "인증 누락·만료·폐기·제한 회원",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "403",
		description = "접근 거부",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "503",
		description = "인증 저장소 일시 장애",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	ResponseEntity<CurrentUserResponse> me(
		@io.swagger.v3.oas.annotations.Parameter(hidden = true) LoginPrincipal principal
	);

	@Operation(
		summary = "로그인 토큰 갱신",
		description = """
				AUTH-009. 본문·Access Token 없이 bareum_refresh 쿠키와 X-CSRF-TOKEN 헤더로 호출합니다.
				먼저 GET /api/v1/auth/csrf를 credentials: include로 호출하고 받은 token을 헤더에 넣습니다.
				갱신 요청에도 credentials: include를 사용합니다. 새 Access Token은 본문, 새 Refresh Token은
				HttpOnly 쿠키로 전달합니다. 15분 Access 기간은 최초 로그인부터 최대 8시간의 남은 시간으로
				제한하며 최대 로그인 만료를 연장하지 않습니다. 새 JWT Access·Refresh를 발급합니다. 이전 Refresh는 소비 처리하고 이전 Access는 원래 만료까지 유지합니다.
				사용된 Refresh Token을 재사용하면 해당 로그인 토큰을 폐기합니다. 동시 갱신과 자동 재시도는
				허용하지 않으므로 프론트에서 갱신 요청을 직렬화해야 합니다. 오류는 ProblemDetail로 반환합니다.
				""",
		parameters = {
			@Parameter(
				name = "X-CSRF-TOKEN",
				in = ParameterIn.HEADER,
				required = true,
				description = "CSRF 조회 응답의 마스킹된 token"),
			@Parameter(
				name = "bareum_refresh",
				in = ParameterIn.COOKIE,
				required = true,
				description = "브라우저가 전달하는 HttpOnly Refresh Token")
		})
	@ApiResponse(
		responseCode = "200",
		description = "새 토큰·갱신 쿠키. Cache-Control: no-store",
		content = @Content(
			mediaType = "application/json",
			schema = @Schema(implementation = EmailLoginResponse.class)))
	@ApiResponse(
		responseCode = "401",
		description = "AUTH-0021 유효하지 않은 갱신 토큰 또는 AUTH-0022 재사용",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "403",
		description = "AUTH-0016 제한 회원 또는 AUTH-0018 CSRF 실패",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "429",
		description = "AUTH-0023 로그인 세션별 갱신 요청 제한",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "503",
		description = "AUTH-0019 인증 저장소 일시 장애",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	ResponseEntity<EmailLoginResponse> renew(@Parameter(hidden = true) HttpServletRequest request);

	@Operation(
		summary = "CSRF 토큰 조회",
		description = """
				로그인 토큰 없이 credentials: include로 호출합니다. HttpOnly XSRF-TOKEN 쿠키를 설정하고
				본문에 마스킹된 token과 headerName=X-CSRF-TOKEN을 반환합니다. 갱신 요청에 해당 헤더와
				쿠키를 함께 보냅니다. 프론트는 쿠키를 직접 읽지 않습니다. 응답은 캐시하지 않습니다.
				""")
	@ApiResponse(
		responseCode = "200",
		description = "CSRF 토큰 및 쿠키",
		content = @Content(
			mediaType = "application/json",
			schema = @Schema(implementation = CsrfTokenResponse.class)))
	ResponseEntity<CsrfTokenResponse> csrf(@Parameter(hidden = true) CsrfToken csrf);

	@Operation(summary = "로그아웃",
		description = """
				AUTH-003. bareum_refresh 쿠키로 현재 로그인을 폐기하고 Refresh·CSRF 쿠키를 삭제합니다.
				본문·Access Token 없이 credentials: include와 X-CSRF-TOKEN 헤더로 호출합니다.
				CSRF 헤더는 GET /api/v1/auth/csrf 응답의 마스킹된 token을 사용합니다.
				이메일·Google 가입 방식과 회원 상태에 관계없이 동일하게 처리합니다.
				성공은 본문 없는 204입니다. 반복 요청·누락·알 수 없는·만료된 Refresh Token도
				유효한 CSRF 검증 후 204로 처리합니다. 중복 Refresh 쿠키는 401로 거부합니다.
				같은 로그인에서 갱신 전에 사용한 Refresh Token으로도 해당 로그인을 폐기하되,
				이전 로그인 토큰으로 새 로그인을 폐기하지 않습니다. 만료된 Authorization은 무시합니다.
				프론트는 성공 후 토큰·사용자·문서·분석 상태를 비우고 상태 조회를 중지합니다.
				갱신·로그아웃을 직렬화하고 다음 쿠키 인증 요청 전 CSRF 토큰을 다시 조회합니다.
				""",
		parameters = {
			@Parameter(name = "X-CSRF-TOKEN",
				in = ParameterIn.HEADER,
				required = true,
				description = "CSRF 조회 응답의 마스킹된 token"),
			@Parameter(name = "bareum_refresh",
				in = ParameterIn.COOKIE,
				description = "폐기할 로그인 쿠키. 누락 시에도 CSRF 검증 필요")
		})
	@ApiResponse(responseCode = "204",
		description = "로그인 폐기·쿠키 삭제. 본문 없음, Cache-Control: no-store",
		content = @Content)
	@ApiResponse(responseCode = "401",
		description = "AUTH-0021 중복 Refresh 쿠키",
		content = @Content(mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(responseCode = "403",
		description = "AUTH-0018 CSRF 검증 실패",
		content = @Content(mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(responseCode = "503",
		description = "AUTH-0019 인증 저장소 일시 장애",
		content = @Content(mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	ResponseEntity<Void> logout(@Parameter(hidden = true) HttpServletRequest request);

	@Operation(
		summary = "Google 로그인",
		description = "AUTH-004. Google ID Token의 서명·발급자·대상 Client ID·만료·이메일 인증을 검증합니다. 기존 회원은 LOGIN과"
				+ " 로그인 토큰, HttpOnly 갱신 쿠키를 반환합니다. 신규 회원은 SIGNUP_REQUIRED와 AUTH-011 전용 가입"
				+ " 증명을 반환합니다. GET /api/v1/auth/csrf에서 받은 쿠키와 X-CSRF-TOKEN 헤더를 반드시 함께"
				+ " 전송합니다. 같은 이메일의 LOCAL 계정을 자동 연결하지 않습니다.",
		parameters = @Parameter(
			name = "X-CSRF-TOKEN",
			in = ParameterIn.HEADER,
			required = true,
			description = "GET /api/v1/auth/csrf 응답의 token"))
	@ApiResponse(
		responseCode = "200",
		description = "LOGIN 또는 SIGNUP_REQUIRED",
		content = @Content(
			mediaType = "application/json",
			schema = @Schema(implementation = GoogleLoginResponse.class),
			examples = {
				@ExampleObject(
					name = "기존 회원",
					value = "{\"status\":\"LOGIN\",\"accessToken\":\"<accessToken>\",\"tokenType\":\"Bearer\",\"expiresIn\":900,\"sessionExpiresIn\":28800}"),
				@ExampleObject(
					name = "신규 회원",
					value = "{\"status\":\"SIGNUP_REQUIRED\",\"signupToken\":\"<signupToken>\",\"signupExpiresIn\":600}")
			}))
	@ApiResponse(
		responseCode = "400",
		description = "입력 검증 오류",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail")))
	@ApiResponse(
		responseCode = "401",
		description = "잘못된 Google 인증",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "403",
		description = "제한 계정·CSRF 실패·추가 이메일 확인 필요",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "409",
		description = "기존 이메일 가입 계정",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "429",
		description = "로그인 요청 제한",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "500",
		description = "예기치 않은 서버 오류",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "503",
		description = "Client ID 미설정·Google 공개키 조회 또는 인증 저장소 장애",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	ResponseEntity<GoogleLoginResponse> googleLogin(GoogleLoginRequest request);
}
