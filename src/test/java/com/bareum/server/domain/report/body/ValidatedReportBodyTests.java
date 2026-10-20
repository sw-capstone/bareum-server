package com.bareum.server.domain.report.body;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

class ValidatedReportBodyTests {
	private final BodyJson codec = new BodyJson();

	@Test
	void preservesValidatedBodyWithoutCopyingIt() throws Exception {
		ReportBody body = codec.read(BodyFixtures.contract("1.0.0", "direct-input"));
		ValidatedReportBody validated = ValidatedReportBody.from(body);
		assertSame(body, validated.getBody());
	}

	@Test
	void rejectsNullAndDuplicateIdsBeforeCreatingValidatedBody() throws Exception {
		ReportBody valid = codec.read(BodyFixtures.contract("1.0.0", "direct-input"));
		ReportBody invalid = new ReportBody(valid.schemaVersion(), valid.offsetUnit(), valid.sourceFormat(),
				valid.pages(), List.of(valid.blocks().getFirst(), valid.blocks().getFirst()),
				valid.sections(), valid.warnings());
		for (ReportBody candidate : new ReportBody[] {null, invalid}) {
			var error = assertThrows(ReportException.class, () -> ValidatedReportBody.from(candidate));
			assertEquals(ReportErrorCode.INVALID_CONTENT, error.getBaseErrorCode());
		}
	}
}
