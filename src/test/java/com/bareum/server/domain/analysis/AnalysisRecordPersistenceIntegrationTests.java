package com.bareum.server.domain.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.analysis.entity.AnalysisResultReport;
import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.analysis.entity.ReportAnalysisStep;
import com.bareum.server.domain.analysis.enums.AnalysisStatus;
import com.bareum.server.domain.analysis.enums.AnalysisStepCode;
import com.bareum.server.domain.analysis.exception.AnalysisErrorCode;
import com.bareum.server.domain.analysis.exception.AnalysisException;
import com.bareum.server.domain.analysis.repository.AnalysisResultReportRepository;
import com.bareum.server.domain.analysis.service.command.AnalysisRecordCommandService;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.body.BodyJson;
import com.bareum.server.domain.report.body.ValidatedReportBody;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.enums.ReportType;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class AnalysisRecordPersistenceIntegrationTests {
	private static final Instant STARTED_AT = Instant.parse("2026-11-23T10:00:00Z");

	@Autowired
	private AnalysisRecordCommandService commandService;
	@Autowired
	private AnalysisResultReportRepository results;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void softDeleteHidesTheResultAndReleasesTheVisibleSuccessSlot() throws Exception {
		var owner = persistMember();
		var content = persistContent(owner);
		var firstResult = persistResult(content);
		long analysisId = firstResult.getReportAnalysis().getId();
		long resultId = firstResult.getId();
		assertEquals(resultId, results.findVisibleByAnalysisId(owner.getId(), analysisId).orElseThrow().getId());

		commandService.delete(owner.getId(), analysisId);
		entityManager.flush();
		entityManager.clear();
		Instant deletedAt = entityManager.find(ReportAnalysis.class, analysisId).getDeletedAt();
		assertNotNull(deletedAt);
		commandService.delete(owner.getId(), analysisId);
		assertEquals(deletedAt, entityManager.find(ReportAnalysis.class, analysisId).getDeletedAt());
		assertTrue(results.findVisibleByAnalysisId(owner.getId(), analysisId).isEmpty());
		assertEquals(1, jdbc.queryForObject(
				"SELECT count(*) FROM analysis_result_report WHERE id = ?", Integer.class, resultId));

		var replacement = persistResult(entityManager.find(ReportContent.class, content.getId()));
		var visible = results.findVisibleByMemberId(owner.getId(), PageRequest.of(0, 10));
		assertEquals(List.of(replacement.getId()),
				visible.getContent().stream().map(AnalysisResultReport::getId).toList());
	}

	@Test
	void visibleRecordsRequireOwnershipAndASuccessfulAnalysisWithAResultReport() throws Exception {
		var owner = persistMember();
		var outsider = persistMember();
		var visibleResult = persistResult(persistContent(owner));
		persistResult(persistContent(outsider));
		var failed = persistAnalysis(persistContent(owner));
		failed.start(STARTED_AT);
		failed.fail("CHECK_FAILED", "검사 실패", STARTED_AT.plusSeconds(1));
		var withoutResult = persistAnalysis(persistContent(owner));
		startReportGeneration(withoutResult).completeReportGeneration(0, null, STARTED_AT.plusSeconds(1));
		entityManager.flush();
		entityManager.clear();

		var records = results.findVisibleByMemberId(owner.getId(), PageRequest.of(0, 10)).getContent();
		assertEquals(List.of(visibleResult.getId()), records.stream().map(AnalysisResultReport::getId).toList());
		assertTrue(
				results.findVisibleByAnalysisId(outsider.getId(), visibleResult.getReportAnalysis().getId()).isEmpty());
		assertEquals(AnalysisErrorCode.ANALYSIS_NOT_FOUND, assertThrows(AnalysisException.class,
				() -> commandService.delete(outsider.getId(), visibleResult.getReportAnalysis().getId()))
				.getBaseErrorCode());
	}

	@ParameterizedTest
	@EnumSource(value = AnalysisStatus.class, names = {"PENDING", "PROCESSING"})
	void rejectsDeletingAnAnalysisThatHasNotFinished(AnalysisStatus status) throws Exception {
		var owner = persistMember();
		var analysis = persistAnalysis(persistContent(owner));
		if (status == AnalysisStatus.PROCESSING) {
			startReportGeneration(analysis);
		}
		entityManager.flush();
		entityManager.clear();

		assertEquals(AnalysisErrorCode.ANALYSIS_IN_PROGRESS, assertThrows(AnalysisException.class,
				() -> commandService.delete(owner.getId(), analysis.getId())).getBaseErrorCode());
		var reloaded = entityManager.find(ReportAnalysis.class, analysis.getId());
		assertEquals(status, reloaded.getStatus());
		assertNull(reloaded.getDeletedAt());
	}

	private AnalysisResultReport persistResult(ReportContent content) {
		var analysis = persistAnalysis(content);
		var generation = startReportGeneration(analysis);
		var result = generation.completeReportGeneration(0, "분석 요약", STARTED_AT.plusSeconds(1));
		entityManager.persist(result);
		return result;
	}

	private ReportAnalysisStep startReportGeneration(ReportAnalysis analysis) {
		analysis.start(STARTED_AT);
		analysis.recordScore(new BigDecimal("85.00"));
		var generation = ReportAnalysisStep.create(AnalysisStepCode.REPORT_GENERATION, analysis);
		generation.start(STARTED_AT);
		entityManager.persist(generation);
		return generation;
	}

	private ReportAnalysis persistAnalysis(ReportContent content) {
		var analysis = ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1");
		entityManager.persist(analysis);
		return analysis;
	}

	private Member persistMember() {
		var member = Member.createLocal("record-" + UUID.randomUUID() + "@example.invalid", "tester", "hash");
		entityManager.persist(member);
		return member;
	}

	private ReportContent persistContent(Member member) throws Exception {
		var report = Report.create(member, ReportType.PLAN);
		entityManager.persist(report);
		var body = new BodyJson().read(Files.readString(
				Path.of("packages/contracts/report-content/1.0.0/examples/pdf.example.json")));
		var content = ReportContent.create(report, ValidatedReportBody.from(body));
		entityManager.persist(content);
		return content;
	}
}
