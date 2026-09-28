package com.bareum.server.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.analysis.enums.AnalysisStatus;
import com.bareum.server.domain.analysis.exception.AnalysisException;
import com.bareum.server.domain.analysis.exception.AnalysisErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.enums.ReportType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

class EntityInvariantTests {

	@Test
	void jsonCopiesProtectFrozenContentAndDrafts() {
		ObjectNode input = JsonNodeFactory.instance.objectNode().put("text", "original");
		ReportContent baseline = ReportContent.create(report(), input);
		input.put("text", "outside mutation");
		assertEquals("original", baseline.getContent().get("text").asString());
		baseline.freeze();
		((ObjectNode) baseline.getContent()).put("text", "getter mutation");
		ReportContent draft = ReportContent.draftFrom(baseline);
		draft.replaceContent(JsonNodeFactory.instance.objectNode().put("text", "edited"));
		assertEquals("original", baseline.getContent().get("text").asString());
		assertEquals("edited", draft.getContent().get("text").asString());
		assertEquals(2, draft.getRevision());
		assertEquals(ReportErrorCode.FROZEN_CONTENT_CANNOT_BE_EDITED, assertThrows(ReportException.class, () -> baseline.replaceContent(input)).getBaseErrorCode());
	}

	@Test
	void cancelledAnalysisRejectsLateCompletion() {
		ReportContent content = ReportContent.create(report(), JsonNodeFactory.instance.objectNode());
		content.freeze();
		ReportAnalysis analysis = ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1");
		Instant now = Instant.now();
		analysis.start(now);
		analysis.cancel(now.plusSeconds(1));
		assertEquals(AnalysisErrorCode.ANALYSIS_NOT_PROCESSING, assertThrows(AnalysisException.class, () -> analysis.succeed(BigDecimal.TEN, now.plusSeconds(2))).getBaseErrorCode());
		assertEquals(AnalysisStatus.CANCELLED, analysis.getStatus());
		assertNull(analysis.getTotalScore());
	}

	@Test
	void analysisRequiresFrozenInput() {
		ReportContent content = ReportContent.create(report(), JsonNodeFactory.instance.objectNode());
		assertEquals(AnalysisErrorCode.INPUT_NOT_FROZEN, assertThrows(AnalysisException.class, () -> ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1")).getBaseErrorCode());
	}

	@Test
	void textReportHasNoFileMetadata() {
		Report report = Report.createFromText(member(), ReportType.PLAN, "title");
		assertNull(report.getSourceObjectKey());
		assertNull(report.getFileFormat());
		assertNull(report.getOriginalFilename());
		assertNull(report.getFileSizeBytes());
	}

	private Report report() {
		return Report.createFromText(member(), ReportType.PLAN, "title");
	}

	private Member member() {
		return Member.createLocal("test@example.com", "tester", "hash");
	}
}
