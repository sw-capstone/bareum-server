package com.bareum.server.global.response;

import com.bareum.server.global.exception.code.BaseErrorCode;
import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;

/**
 * 공통 실패 응답 객체를 만드는 클래스
 */
public final class ProblemDetailFactory {

	private ProblemDetailFactory() {
	}

	public static ProblemDetail create(BaseErrorCode errorCode, String detail, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.getHttpStatus(), detail);
		problem.setTitle(errorCode.getHttpStatus().getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));	// 오류가 발생한 요청 경로
		problem.setProperty("code", errorCode.getCode());
		return problem;
	}
}
