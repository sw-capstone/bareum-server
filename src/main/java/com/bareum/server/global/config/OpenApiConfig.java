package com.bareum.server.global.config;

import com.bareum.server.global.exception.code.CommonErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서 정보와 공통 실패 응답의 구조 및 예시를 정의하는 설정 클래스
 */
@Configuration
public class OpenApiConfig {

	/**
	 * 현재 서버를 기준으로 API를 호출하도록 설정하고 공통 실패 명세를 등록
	 */
	@Bean
	public OpenAPI bareumOpenApi() {
		return new OpenAPI()
				.info(new Info().title("Bareum API").version("0.0.1")
						.description("바름 API 문서"))
				.servers(List.of(new Server().url("/").description("현재 접속 서버")))
				.components(new Components()
						.addSchemas("ProblemDetail", problemSchema())
						.addSchemas("ValidationProblemDetail", validationProblemSchema())
						.addSchemas("ValidationErrorDetail", validationErrorSchema())
						.addExamples("InternalServerError", problemExample(CommonErrorCode.INTERNAL_SERVER_ERROR))
						.addExamples("InvalidRequestBody", problemExample(CommonErrorCode.INVALID_REQUEST_BODY))
						.addExamples("ValidationFailed", validationExample()));
	}

	/**
	 * ProblemDetail 표준 필드와 서비스 에러 코드의 문서 구조를 정의
	 */
	private ObjectSchema problemSchema() {
		ObjectSchema schema = new ObjectSchema();
		schema.addProperty("type", new StringSchema().format("uri").example("about:blank"));
		schema.addProperty("title", new StringSchema().description("HTTP 상태의 일반적인 제목"));
		schema.addProperty("status", new IntegerSchema().description("HTTP 응답 상태"));
		schema.addProperty("detail", new StringSchema().description("사용자에게 공개할 안내 문구"));
		schema.addProperty("instance", new StringSchema().format("uri-reference").description("오류가 발생한 요청 경로"));
		schema.addProperty("code", new StringSchema().description("서비스 오류 식별자").example("COMMON-0001"));
		schema.setRequired(List.of("title", "status", "detail", "instance", "code"));
		return schema;
	}

	/**
	 * 검증 실패 응답에 입력 항목별 오류 목록을 추가
	 */
	private ObjectSchema validationProblemSchema() {
		ObjectSchema schema = problemSchema();
		schema.addProperty("errors", new ArraySchema()
				.items(new Schema<>().$ref("#/components/schemas/ValidationErrorDetail")));
		schema.addRequiredItem("errors");
		return schema;
	}

	/**
	 * 필드 오류와 객체 전체 오류를 표현하는 검증 상세 구조를 정의
	 */
	private ObjectSchema validationErrorSchema() {
		Schema<String> field = new Schema<>();
		field.setTypes(Set.of("string", "null"));
		field.setDescription("입력 항목 이름이며 객체 전체 오류는 null");
		ObjectSchema schema = new ObjectSchema();
		schema.addProperty("field", field);
		schema.addProperty("message", new StringSchema().description("입력값 수정 안내"));
		schema.setRequired(List.of("field", "message"));
		return schema;
	}

	/**
	 * 실제 공통 에러 코드의 상태와 안내 문구로 실패 응답 예시를 생성
	 */
	private Example problemExample(CommonErrorCode errorCode) {
		return new Example().value(Map.of(
				"type", "about:blank",
				"title", errorCode.getHttpStatus().getReasonPhrase(),
				"status", errorCode.getHttpStatus().value(),
				"detail", errorCode.getMessage(),
				"instance", "/api/example",
				"code", errorCode.getCode()));
	}

	/**
	 * 검증 실패 응답의 필드별 안내 예시를 생성
	 */
	private Example validationExample() {
		CommonErrorCode errorCode = CommonErrorCode.VALIDATION_FAILED;
		return new Example().value(Map.of(
				"type", "about:blank",
				"title", errorCode.getHttpStatus().getReasonPhrase(),
				"status", errorCode.getHttpStatus().value(),
				"detail", errorCode.getMessage(),
				"instance", "/api/example",
				"code", errorCode.getCode(),
				"errors", List.of(Map.of("field", "name", "message", "이름을 입력해주세요."))));
	}
}
