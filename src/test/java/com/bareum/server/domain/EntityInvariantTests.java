package com.bareum.server.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.analysis.enums.AnalysisStatus;
import com.bareum.server.domain.analysis.exception.AnalysisErrorCode;
import com.bareum.server.domain.analysis.exception.AnalysisException;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.body.ReportBody;
import com.bareum.server.domain.report.body.ReportBody.Block;
import com.bareum.server.domain.report.body.ReportBody.TextBlock;
import com.bareum.server.domain.report.body.ReportBody.Unit;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.enums.SourceFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

class EntityInvariantTests {

	@Test
	void immutableBodiesProtectFrozenContentAndDrafts() {
		List<Block> blocks = new ArrayList<>(body("original").blocks());
		ReportBody input = new ReportBody("1.0.0", "UNICODE_CODE_POINT", SourceFormat.TEXT,
				List.of(), blocks, List.of(), List.of());
		ReportContent baseline = ReportContent.create(report(), input);
		blocks.clear();
		assertEquals("original", text(baseline));
		baseline.freeze();
		assertThrows(UnsupportedOperationException.class, () -> baseline.getContent().blocks().clear());
		assertThrows(UnsupportedOperationException.class,
				() -> ((TextBlock)baseline.getContent().blocks().getFirst()).units().clear());
		ReportContent draft = ReportContent.draftFrom(baseline);
		draft.replaceContent(body("edited"));
		assertEquals("original", text(baseline));
		assertEquals("edited", text(draft));
		assertEquals(2, draft.getRevision());
		assertEquals(ReportErrorCode.FROZEN_CONTENT_CANNOT_BE_EDITED,
				assertThrows(ReportException.class, () -> baseline.replaceContent(input)).getBaseErrorCode());
	}

	@Test
	void creationAndReplacementRejectInvalidBodiesWithoutChangingDraft() {
		ReportBody valid = body("original");
		ReportBody invalid = new ReportBody(valid.schemaVersion(), valid.offsetUnit(), valid.sourceFormat(),
				valid.pages(), List.of(valid.blocks().getFirst(), valid.blocks().getFirst()),
				valid.sections(), valid.warnings());
		ReportContent draft = ReportContent.create(report(), valid);
		for (ReportBody candidate : new ReportBody[] {null, invalid}) {
			assertEquals(ReportErrorCode.INVALID_CONTENT,
					assertThrows(ReportException.class, () -> ReportContent.create(report(), candidate)).getBaseErrorCode());
			assertEquals(ReportErrorCode.INVALID_CONTENT,
					assertThrows(ReportException.class, () -> draft.replaceContent(candidate)).getBaseErrorCode());
			assertEquals(valid, draft.getContent());
			assertEquals(1, draft.getRevision());
		}
	}

	@Test
	void cancelledAnalysisRejectsLateCompletion() {
		ReportContent content = ReportContent.create(report(), body("original"));
		content.freeze();
		ReportAnalysis analysis = ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1");
		Instant now = Instant.now();
		analysis.start(now);
		analysis.cancel(now.plusSeconds(1));
		assertEquals(AnalysisErrorCode.ANALYSIS_NOT_PROCESSING,
				assertThrows(AnalysisException.class, () -> analysis.succeed(BigDecimal.TEN, now.plusSeconds(2)))
						.getBaseErrorCode());
		assertEquals(AnalysisStatus.CANCELLED, analysis.getStatus());
		assertNull(analysis.getTotalScore());
	}

	@Test
	void analysisRequiresFrozenInput() {
		ReportContent content = ReportContent.create(report(), body("original"));
		assertEquals(AnalysisErrorCode.INPUT_NOT_FROZEN, assertThrows(AnalysisException.class,
				() -> ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1")).getBaseErrorCode());
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

	private ReportBody body(String text) {
		return new ReportBody("1.0.0", "UNICODE_CODE_POINT", SourceFormat.TEXT, List.of(),
				List.of(new TextBlock(UUID.randomUUID(), "para", null, null, text, text,
						List.of(new Unit(UUID.randomUUID(), 0, text.codePointCount(0, text.length()), text)),
						List.of(), null)), List.of(), List.of());
	}

	private String text(ReportContent content) {
		return ((TextBlock)content.getContent().blocks().getFirst()).text();
	}

	private Member member() {
		return Member.createLocal("test@example.com", "tester", "hash");
	}
}
