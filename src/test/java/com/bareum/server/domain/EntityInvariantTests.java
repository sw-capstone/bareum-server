package com.bareum.server.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.analysis.entity.ReportAnalysisStep;
import com.bareum.server.domain.analysis.enums.AnalysisStatus;
import com.bareum.server.domain.analysis.enums.AnalysisStepCode;
import com.bareum.server.domain.analysis.enums.AnalysisStepStatus;
import com.bareum.server.domain.analysis.exception.AnalysisErrorCode;
import com.bareum.server.domain.analysis.exception.AnalysisException;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.body.ReportBody;
import com.bareum.server.domain.report.body.ReportBody.Block;
import com.bareum.server.domain.report.body.ReportBody.TextBlock;
import com.bareum.server.domain.report.body.ReportBody.Unit;
import com.bareum.server.domain.report.body.ValidatedReportBody;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.enums.SourceFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

class EntityInvariantTests {
	private static final Instant STARTED_AT = Instant.parse("2026-11-23T10:00:00Z");

	@Test
	void parsedBodyAndItsTextUnitsAreImmutable() {
		List<Block> blocks = new ArrayList<>(body("original").blocks());
		ReportBody input = new ReportBody("1.0.0", "UNICODE_CODE_POINT", SourceFormat.TEXT,
				List.of(), blocks, List.of(), List.of());
		ValidatedReportBody validated = ValidatedReportBody.from(input);
		ReportContent baseline = ReportContent.create(report(), validated);
		blocks.clear();
		assertEquals("original", text(baseline));
		assertThrows(UnsupportedOperationException.class, () -> baseline.getContent().blocks().clear());
		assertThrows(UnsupportedOperationException.class,
				() -> ((TextBlock)baseline.getContent().blocks().getFirst()).units().clear());
	}

	@Test
	void creationRejectsMissingParsedBody() {
		assertEquals(ReportErrorCode.INVALID_CONTENT,
				assertThrows(ReportException.class, () -> ReportContent.create(report(), null)).getBaseErrorCode());
	}

	@Test
	void scoringCompletesBeforeTheReportAndKeepsTheAnalysisInProgress() {
		var analysis = processingAnalysis();
		var scoring = ReportAnalysisStep.create(AnalysisStepCode.SCORING, analysis);
		var generation = ReportAnalysisStep.create(AnalysisStepCode.REPORT_GENERATION, analysis);
		scoring.start(STARTED_AT);
		assertEquals(AnalysisErrorCode.SCORE_NOT_RECORDED,
				assertThrows(AnalysisException.class, () -> generation.start(STARTED_AT)).getBaseErrorCode());

		analysis.recordScore(BigDecimal.TEN);
		scoring.succeed(STARTED_AT.plusSeconds(1));
		analysis.recordScore(new BigDecimal("10.00"));
		assertEquals(AnalysisErrorCode.INVALID_SCORE,
				assertThrows(AnalysisException.class, () -> analysis.recordScore(BigDecimal.ONE)).getBaseErrorCode());
		assertEquals(BigDecimal.TEN, analysis.getTotalScore());
		assertEquals(AnalysisStepStatus.SUCCEEDED, scoring.getStatus());
		assertEquals(AnalysisStatus.PROCESSING, analysis.getStatus());
		assertNull(analysis.getFinishedAt());

		generation.start(STARTED_AT.plusSeconds(2));
		generation.completeReportGeneration(0, STARTED_AT.plusSeconds(3));
		assertEquals(AnalysisStatus.SUCCEEDED, analysis.getStatus());
		assertEquals(AnalysisStepStatus.SUCCEEDED, generation.getStatus());
		assertEquals(generation.getFinishedAt(), analysis.getFinishedAt());
	}

	@Test
	void reportGenerationRetriesTheSameStepOnceAndIgnoresTheOldAttempt() {
		var analysis = processingAnalysis();
		analysis.recordScore(BigDecimal.TEN);
		var generation = ReportAnalysisStep.create(AnalysisStepCode.REPORT_GENERATION, analysis);
		generation.start(STARTED_AT.plusSeconds(1));
		Instant retryStartedAt = STARTED_AT.plusSeconds(2);
		generation.retryReportGeneration(retryStartedAt);

		assertEquals(1, generation.getRetryCount());
		assertEquals(retryStartedAt, generation.getStartedAt());
		assertEquals(AnalysisStepStatus.PROCESSING, generation.getStatus());
		assertEquals(AnalysisStatus.PROCESSING, analysis.getStatus());
		assertEquals(AnalysisErrorCode.STALE_REPORT_GENERATION_ATTEMPT,
				assertThrows(AnalysisException.class, () -> generation.retryReportGeneration(STARTED_AT.plusSeconds(3)))
						.getBaseErrorCode());
		assertEquals(AnalysisErrorCode.STALE_REPORT_GENERATION_ATTEMPT,
				assertThrows(AnalysisException.class,
						() -> generation.completeReportGeneration(0, STARTED_AT.plusSeconds(3))).getBaseErrorCode());
		assertEquals(AnalysisStatus.PROCESSING, analysis.getStatus());

		generation.completeReportGeneration(1, STARTED_AT.plusSeconds(4));
		assertEquals(AnalysisStatus.SUCCEEDED, analysis.getStatus());
		assertEquals(AnalysisStepStatus.SUCCEEDED, generation.getStatus());
		assertEquals(BigDecimal.TEN, analysis.getTotalScore());
	}

	@Test
	void finalReportGenerationFailurePreservesTheScoreAndRecordsTheCause() {
		var analysis = processingAnalysis();
		analysis.recordScore(BigDecimal.TEN);
		var generation = ReportAnalysisStep.create(AnalysisStepCode.REPORT_GENERATION, analysis);
		generation.start(STARTED_AT.plusSeconds(1));
		generation.retryReportGeneration(STARTED_AT.plusSeconds(2));
		Instant finishedAt = STARTED_AT.plusSeconds(3);
		generation.failReportGeneration(1, "REPORT_TIMEOUT", "리포트 생성 시간 초과", finishedAt);

		assertEquals(AnalysisStatus.REPORT_GENERATION_FAILED, analysis.getStatus());
		assertEquals(AnalysisStepStatus.FAILED, generation.getStatus());
		assertEquals(BigDecimal.TEN, analysis.getTotalScore());
		assertEquals("REPORT_TIMEOUT", analysis.getFailureCode());
		assertEquals(analysis.getFailureCode(), generation.getFailureCode());
		assertEquals("리포트 생성 시간 초과", analysis.getFailureReason());
		assertEquals(finishedAt, analysis.getFinishedAt());
		assertEquals(finishedAt, generation.getFinishedAt());
	}

	@Test
	void cancelledAnalysisRejectsLateCompletion() {
		var analysis = processingAnalysis();
		analysis.recordScore(BigDecimal.TEN);
		var generation = ReportAnalysisStep.create(AnalysisStepCode.REPORT_GENERATION, analysis);
		generation.start(STARTED_AT.plusSeconds(1));
		analysis.cancel(STARTED_AT.plusSeconds(2));
		assertEquals(AnalysisErrorCode.ANALYSIS_NOT_PROCESSING,
				assertThrows(AnalysisException.class,
						() -> generation.completeReportGeneration(0, STARTED_AT.plusSeconds(3))).getBaseErrorCode());
		assertEquals(AnalysisStatus.CANCELLED, analysis.getStatus());
		assertNull(analysis.getTotalScore());
	}

	@Test
	void analysisUsesTheFixedParsedBody() {
		ReportContent content = ReportContent.create(report(), ValidatedReportBody.from(body("original")));
		assertSame(content, ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1").getContent());
	}

	private ReportAnalysis processingAnalysis() {
		ReportContent content = ReportContent.create(report(), ValidatedReportBody.from(body("original")));
		ReportAnalysis analysis = ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1");
		analysis.start(STARTED_AT);
		return analysis;
	}

	private Report report() {
		return Report.create(member(), ReportType.PLAN);
	}

	private ReportBody body(String text) {
		return new ReportBody("1.0.0", "UNICODE_CODE_POINT", SourceFormat.TEXT, List.of(),
				List.of(new TextBlock(UUID.randomUUID(), "para", null, null, text, text,
						List.of(new Unit(UUID.randomUUID(), 0, text.codePointCount(0, text.length()), text)),
						List.of(), null, null)),
				List.of(), List.of());
	}

	private String text(ReportContent content) {
		return ((TextBlock)content.getContent().blocks().getFirst()).text();
	}

	private Member member() {
		return Member.createLocal("test@example.com", "tester", "hash");
	}
}
