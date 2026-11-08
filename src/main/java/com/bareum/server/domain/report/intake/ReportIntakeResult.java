package com.bareum.server.domain.report.intake;

import tools.jackson.databind.JsonNode;

/** 새 접수와 멱등 재전송에 같은 상태 코드와 본문을 반환한다. */
public record ReportIntakeResult(
	int status,
	JsonNode body
) {
	public ReportIntakeResult {
		body = body.deepCopy();
	}

	@Override
	public JsonNode body() {
		return body.deepCopy();
	}
}
