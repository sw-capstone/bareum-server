package com.bareum.server.global.exception.handler;

import com.bareum.server.global.response.ValidationErrorDetail;

import com.bareum.server.global.exception.BusinessException;

import com.bareum.server.global.exception.code.BaseErrorCode;

import com.bareum.server.global.exception.code.CommonErrorCode;
import com.bareum.server.global.response.ProblemDetailFactory;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * API 요청 처리 중 전달된 예외를 실패 응답으로 변환하고 로그를 기록하는 전역 예외 처리 클래스
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	/**
	 * 업무 예외를 에러 코드와 안내 메시지로 응답하고, 지정된 수준으로 로그를 기록
	 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ProblemDetail> handleBusinessException(
			BusinessException exception, HttpServletRequest request) {
		BaseErrorCode errorCode = exception.getBaseErrorCode();
		Level logLevel = errorCode.getLogLevel();
		LoggingEventBuilder logEvent = log.atLevel(logLevel);
		if (logLevel == Level.ERROR) {
			logEvent.setCause(exception);
		}
		logEvent.log("Business exception: domain={}, code={}", exception.getDomain(), errorCode.getCode());
		ProblemDetail problem = ProblemDetailFactory.create(errorCode, exception.getMessage(), request);
		return ResponseEntity.status(errorCode.getHttpStatus()).body(problem);
	}

	/**
	 * 잘못된 JSON과 필수 요청 본문 누락을 공통 실패 응답으로 처리
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
			HttpMessageNotReadableException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return createRequestErrorResponse(exception, CommonErrorCode.INVALID_REQUEST_BODY, headers, status, request);
	}

	/**
	 * 필수 요청 파라미터 누락을 공통 실패 응답으로 처리
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleMissingServletRequestParameter(
			MissingServletRequestParameterException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return createRequestErrorResponse(exception, CommonErrorCode.MISSING_REQUEST_PARAMETER, headers, status, request);
	}

	/**
	 * 요청 파라미터의 타입 불일치를 공통 실패 응답으로 처리
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleTypeMismatch(
			TypeMismatchException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return createRequestErrorResponse(exception, CommonErrorCode.INVALID_PARAMETER_TYPE, headers, status, request);
	}

	/**
	 * 지원하지 않는 HTTP 메서드를 허용 메서드 헤더와 함께 실패 응답으로 처리
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleHttpRequestMethodNotSupported(
			HttpRequestMethodNotSupportedException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return createRequestErrorResponse(exception, CommonErrorCode.METHOD_NOT_ALLOWED, headers, status, request);
	}

	/**
	 * 지원하지 않는 요청 데이터 형식을 공통 실패 응답으로 처리
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleHttpMediaTypeNotSupported(
			HttpMediaTypeNotSupportedException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return createRequestErrorResponse(exception, CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, headers, status, request);
	}

	/**
	 * 요청 DTO의 @Valid 검증 실패를 필드별 오류와 객체 전체 오류 목록으로 응답
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		List<ValidationErrorDetail> errors = exception.getBindingResult().getAllErrors().stream()
				.map(error -> toValidationDetail(error, null))
				.toList();
		return createValidationResponse(exception, headers, status, request, errors);
	}

	/**
	 * 컨트롤러 입력값 검증 실패는 HTTP 400으로, 반환값 검증 실패는 HTTP 500으로 처리
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
			HandlerMethodValidationException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (exception.isForReturnValue()) {
			return createInternalServerErrorResponse(exception, headers, status, request);
		}
		List<ValidationErrorDetail> errors = new ArrayList<>();
		for (ParameterValidationResult result : exception.getParameterValidationResults()) {
			String field = getParameterName(result.getMethodParameter());
			for (MessageSourceResolvable error : result.getResolvableErrors()) {
				errors.add(toValidationDetail(error, field));
			}
		}
		for (MessageSourceResolvable error : exception.getCrossParameterValidationResults()) {
			errors.add(toValidationDetail(error, null));
		}
		return createValidationResponse(exception, headers, status, request, errors);
	}

	/**
	 * MethodValidationException을 내부 검증 오류로 보고 HTTP 500으로 처리
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleMethodValidationException(
			MethodValidationException exception, HttpHeaders headers,
			HttpStatus status, WebRequest request) {
		return createInternalServerErrorResponse(exception, headers, status, request);
	}

	/**
	 * 응답 본문 변환 실패를 공통 서버 오류 응답과 원인 로그로 처리
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleHttpMessageNotWritable(
			HttpMessageNotWritableException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return createInternalServerErrorResponse(exception, headers, status, request);
	}

	/**
	 * 서버의 타입 변환 지원 오류를 공통 서버 오류 응답과 원인 로그로 처리
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleConversionNotSupported(
			ConversionNotSupportedException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return createInternalServerErrorResponse(exception, headers, status, request);
	}

	/**
	 * 예상하지 못한 예외를 ERROR 로그로 기록하고, 내부 내용을 숨긴 HTTP 500 응답을 반환
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ProblemDetail> handleUnexpectedException(
			Exception exception, HttpServletRequest request) {
		log.error("Unexpected server exception", exception);
		BaseErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(errorCode.getHttpStatus())
				.body(ProblemDetailFactory.create(errorCode, errorCode.getMessage(), request));
	}

	/**
	 * 요청 오류의 공통 실패 응답을 만들고 전달받은 HTTP 상태와 헤더를 유지
	 */
	private @Nullable ResponseEntity<Object> createRequestErrorResponse(
			Exception exception, BaseErrorCode errorCode, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetailFactory.create(errorCode, errorCode.getMessage(),
				((ServletWebRequest) request).getRequest());
		problem.setStatus(status.value());
		return handleExceptionInternal(exception, problem, headers, status, request);
	}

	/**
	 * 검증 실패 응답에 errors 목록을 추가하고, 전달받은 HTTP 상태와 헤더를 유지
	 */
	private @Nullable ResponseEntity<Object> createValidationResponse(
			Exception exception, HttpHeaders headers, HttpStatusCode status,
			WebRequest request, List<ValidationErrorDetail> errors) {
		BaseErrorCode errorCode = CommonErrorCode.VALIDATION_FAILED;
		ProblemDetail problem = ProblemDetailFactory.create(errorCode, errorCode.getMessage(),
				((ServletWebRequest) request).getRequest());
		problem.setStatus(status.value());
		problem.setProperty("errors", errors);
		return handleExceptionInternal(exception, problem, headers, status, request);
	}

	/**
	 * 서버 오류를 ERROR 로그로 기록하고, 일반 안내 문구로 공통 실패 응답을 생성
	 */
	private @Nullable ResponseEntity<Object> createInternalServerErrorResponse(
			Exception exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		log.error("Internal server exception", exception);
		BaseErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
		ProblemDetail problem = ProblemDetailFactory.create(errorCode, errorCode.getMessage(),
				((ServletWebRequest) request).getRequest());
		return handleExceptionInternal(exception, problem, headers, status, request);
	}

	/**
	 * 검증 오류 하나를 필드 이름과 안내 메시지를 담은 ValidationErrorDetail로 변환
	 */
	private ValidationErrorDetail toValidationDetail(
			MessageSourceResolvable error, @Nullable String parameterName) {
		String field = parameterName;
		if (error instanceof FieldError fieldError) {
			field = fieldError.getField();
		} else if (error instanceof ObjectError) {
			field = null;
		}
		String message = error.getDefaultMessage();
		return new ValidationErrorDetail(field,
				StringUtils.hasText(message) ? message : CommonErrorCode.VALIDATION_FAILED.getMessage());
	}

	/**
	 * 요청 어노테이션에 지정한 이름을 우선 사용하고, 없으면 Java 파라미터 이름을 반환
	 */
	private @Nullable String getParameterName(MethodParameter parameter) {
		RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
		if (requestParam != null) {
			if (StringUtils.hasText(requestParam.name())) {
				return requestParam.name();
			}
			if (StringUtils.hasText(requestParam.value())) {
				return requestParam.value();
			}
		}
		PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
		if (pathVariable != null) {
			if (StringUtils.hasText(pathVariable.name())) {
				return pathVariable.name();
			}
			if (StringUtils.hasText(pathVariable.value())) {
				return pathVariable.value();
			}
		}
		return parameter.getParameterName();
	}
}
