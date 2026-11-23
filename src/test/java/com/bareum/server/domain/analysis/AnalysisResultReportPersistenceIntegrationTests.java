package com.bareum.server.domain.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.analysis.entity.AnalysisResultReport;
import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.analysis.entity.ReportAnalysisStep;
import com.bareum.server.domain.analysis.enums.AnalysisStatus;
import com.bareum.server.domain.analysis.enums.AnalysisStepCode;
import com.bareum.server.domain.analysis.enums.AnalysisStepStatus;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.body.BodyJson;
import com.bareum.server.domain.report.body.ValidatedReportBody;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.enums.ReportType;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class AnalysisResultReportPersistenceIntegrationTests {
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void storesOneResultReportPerAnalysisAndReadsTheSourceFromTheAnalysis() throws Exception {
		var member = Member.createLocal("analysis-" + UUID.randomUUID() + "@example.invalid", "tester", "hash");
		entityManager.persist(member);
		var report = Report.create(member, ReportType.PLAN);
		entityManager.persist(report);
		var body = new BodyJson().read(Files.readString(
				Path.of("packages/contracts/report-content/1.0.0/examples/pdf.example.json")));
		var content = ReportContent.create(report, ValidatedReportBody.from(body));
		entityManager.persist(content);

		var analysis = ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1");
		var startedAt = Instant.parse("2026-11-23T10:00:00Z");
		analysis.start(startedAt);
		analysis.recordScore(new BigDecimal("85.00"));
		entityManager.persist(analysis);
		var generation = ReportAnalysisStep.create(AnalysisStepCode.REPORT_GENERATION, analysis);
		generation.start(startedAt);
		entityManager.persist(generation);

		var finishedAt = startedAt.plusSeconds(1);
		var result = generation.completeReportGeneration(0, "분석 요약", finishedAt);
		entityManager.persist(result);
		entityManager.flush();
		entityManager.clear();

		var reloaded = entityManager.find(AnalysisResultReport.class, result.getId());
		assertEquals("분석 요약", reloaded.getSummary());
		assertEquals(finishedAt, reloaded.getGeneratedAt());
		assertEquals(content.getId(), reloaded.getReportAnalysis().getContent().getId());
		assertEquals(new BigDecimal("85.00"), reloaded.getReportAnalysis().getTotalScore());
		assertEquals(AnalysisStatus.SUCCEEDED, reloaded.getReportAnalysis().getStatus());
		assertEquals(AnalysisStepStatus.SUCCEEDED,
				entityManager.find(ReportAnalysisStep.class, generation.getId()).getStatus());
		assertThrows(DuplicateKeyException.class, () -> jdbc.update(
				"INSERT INTO analysis_result_report (report_analysis_id, generated_at) VALUES (?, now())",
				analysis.getId()));
	}
}
