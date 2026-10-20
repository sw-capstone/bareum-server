package com.bareum.server.domain.report.body;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON과 공통 본문 객체 사이의 변환을 담당하는 클래스
 */
public final class BodyJson {
	private final JsonMapper mapper = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.build();
	private final BodyValidator validator = new BodyValidator();

	// JSON 문자열을 본문 객체로 변환
	public ReportBody read(String json) {
		try {
			ReportBody body = mapper.readValue(json, ReportBody.class);
			validator.validate(body);
			return body;
		} catch (JacksonException exception) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
	}

	public JsonNode tree(ReportBody body) {
		validator.validate(body);
		return mapper.valueToTree(body);
	}
}
