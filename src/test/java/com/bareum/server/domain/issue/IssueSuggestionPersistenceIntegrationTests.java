package com.bareum.server.domain.issue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.analysis.entity.ReportAnalysisAreaResult;
import com.bareum.server.domain.analysis.enums.AnalysisAreaCode;
import com.bareum.server.domain.issue.entity.Issue;
import com.bareum.server.domain.issue.entity.IssueEvidence;
import com.bareum.server.domain.issue.entity.IssueGroup;
import com.bareum.server.domain.issue.entity.IssueSuggestion;
import com.bareum.server.domain.issue.enums.EvidenceType;
import com.bareum.server.domain.issue.enums.IssueScope;
import com.bareum.server.domain.issue.enums.IssueSeverity;
import com.bareum.server.domain.issue.enums.SuggestionGenerationType;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.body.BodyJson;
import com.bareum.server.domain.report.body.ValidatedReportBody;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.enums.ReportType;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Transactional
class IssueSuggestionPersistenceIntegrationTests {
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void storesOneSuggestionForTheGroupAndKeepsTheEvidenceOnTheOriginalIssue() throws Exception {
		var member = Member.createLocal("issue-" + UUID.randomUUID() + "@example.invalid", "tester", "hash");
		entityManager.persist(member);
		var report = Report.create(member, ReportType.PLAN);
		entityManager.persist(report);
		var body = new BodyJson().read(Files.readString(
				Path.of("packages/contracts/report-content/1.0.0/examples/pdf.example.json")));
		var content = ReportContent.create(report, ValidatedReportBody.from(body));
		entityManager.persist(content);
		var analysis = ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1");
		entityManager.persist(analysis);
		var area = ReportAnalysisAreaResult.create(analysis, null, AnalysisAreaCode.REGULATION, null);
		entityManager.persist(area);
		var group = IssueGroup.forTarget(analysis, IssueScope.SENTENCE, "sentence-1");
		entityManager.persist(group);
		var firstIssue = Issue.create(area, group, IssueSeverity.HIGH, "근거 누락", "근거 없음", null);
		var secondIssue = Issue.create(area, group, IssueSeverity.LOW, "표현 오류", "표현 불명확", null);
		entityManager.persist(firstIssue);
		entityManager.persist(secondIssue);
		var evidence = IssueEvidence.create(firstIssue, EvidenceType.LAW, "근거 문서", null, "인용문",
				null, null, null);
		entityManager.persist(evidence);

		var changes = JsonMapper.builder().build().readTree("[{\"replacement\":\"수정 문장\"}]");
		var suggestion = IssueSuggestion.create(group, changes, SuggestionGenerationType.LLM);
		entityManager.persist(suggestion);
		entityManager.flush();
		entityManager.clear();

		var reloaded = entityManager.find(IssueSuggestion.class, suggestion.getId());
		assertEquals(group.getId(), reloaded.getIssueGroup().getId());
		assertEquals("sentence-1", reloaded.getIssueGroup().getTargetId());
		assertEquals(changes, reloaded.getChanges());
		assertEquals(group.getId(), entityManager.find(Issue.class, secondIssue.getId()).getIssueGroup().getId());
		assertEquals(firstIssue.getId(), entityManager.find(IssueEvidence.class, evidence.getId()).getIssue().getId());
		assertThrows(DuplicateKeyException.class, () -> jdbc.update(
				"INSERT INTO issue_suggestion (issue_group_id, changes, generation_type) VALUES (?, '[]', 'LLM')",
				group.getId()));
	}
}
