package com.bareum.server.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.analysis.entity.ReportAnalysisAreaResult;
import com.bareum.server.domain.analysis.entity.ReportAnalysisStep;
import com.bareum.server.domain.analysis.enums.AnalysisAreaCode;
import com.bareum.server.domain.analysis.enums.AnalysisStepCode;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.entity.RefreshToken;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.issue.entity.Issue;
import com.bareum.server.domain.issue.entity.IssueGroup;
import com.bareum.server.domain.issue.entity.IssueEvidence;
import com.bareum.server.domain.issue.entity.IssueSuggestion;
import com.bareum.server.domain.issue.enums.IssueScope;
import com.bareum.server.domain.issue.enums.EvidenceType;
import com.bareum.server.domain.issue.enums.SuggestionGenerationType;
import com.bareum.server.domain.issue.enums.IssueSeverity;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.entity.MemberAuth;
import com.bareum.server.domain.member.enums.OAuthProvider;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.entity.ReportParseAttempt;
import com.bareum.server.domain.report.entity.ReportResult;
import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.term.entity.MemberTerm;
import com.bareum.server.domain.term.entity.Term;
import com.bareum.server.domain.term.enums.TermCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class EntityPersistenceTests {

	@PersistenceContext
	private EntityManager entityManager;

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	@Test
	void persistsAndReloadsAllSeventeenEntitiesWithJsonAndAuditing() {
		Instant now = Instant.now();
		Member member = persist(Member.createLocal("test-" + UUID.randomUUID() + "@example.com", "테스트", "hash"));
		MemberAuth auth = persist(MemberAuth.create(member, OAuthProvider.GOOGLE, UUID.randomUUID().toString(), null, now));
		Term term = persist(Term.create(TermCode.TERMS_OF_SERVICE, UUID.randomUUID().toString().substring(0, 20), true));
		MemberTerm consent = persist(MemberTerm.create(member, term, now, null, true, now));
		RefreshToken token = persist(RefreshToken.create(member, UUID.randomUUID(), now.plusSeconds(3600)));
		EmailVerification verification = persist(EmailVerification.request("new@example.com", VerificationPurpose.SIGNUP, "hash", now.plusSeconds(300)));
		Report report = persist(Report.createFromFile(member, ReportType.PLAN, FileFormat.DOCX, "test/report.docx", "report.docx", 100L, now));
		ReportContent baseline = persist(ReportContent.create(report, JSON.objectNode().put("text", "원문")));
		ReportParseAttempt parsing = persist(ReportParseAttempt.request(report));
		parsing.start(now);
		parsing.complete(baseline, ExtractionQuality.COMPLETE, JSON.arrayNode(), now.plusSeconds(1));
		baseline.freeze();
		ReportAnalysis analysis = persist(ReportAnalysis.request(baseline, UUID.randomUUID(), "rules-v1", "prompt-v1"));
		analysis.start(now);
		analysis.succeed(new BigDecimal("95.25"), now.plusSeconds(2));
		ReportAnalysisStep step = persist(ReportAnalysisStep.create(AnalysisStepCode.STRUCTURE, analysis));
		step.start(now);
		step.succeed(now.plusSeconds(1));
		ReportAnalysisAreaResult area = persist(ReportAnalysisAreaResult.create(analysis, new BigDecimal("4.75"), AnalysisAreaCode.STRUCTURE, JSON.objectNode().put("detail", "누락")));
		IssueGroup group = persist(IssueGroup.forTarget(analysis, IssueScope.SENTENCE, "sentence-1"));
		Issue issue = persist(Issue.create(area, group, IssueSeverity.HIGH, "이슈", "근거", null));
		IssueEvidence evidence = persist(IssueEvidence.create(issue, EvidenceType.LAW, "근거", "제1조", "인용문", null, null, LocalDate.of(2026, 1, 1)));
		IssueSuggestion suggestion = persist(IssueSuggestion.create(group, JSON.arrayNode().add(JSON.objectNode().put("targetId", "sentence-1")), SuggestionGenerationType.LLM));
		ReportContent draft = persist(ReportContent.draftFrom(baseline));
		draft.adoptAsCurrent();
		draft.replaceContent(JSON.objectNode().put("text", "수정문"));
		draft.freeze();
		ReportResult result = persist(ReportResult.request(analysis, draft));
		result.start();
		result.succeed(new BigDecimal("98.50"), now.plusSeconds(3));
		entityManager.flush();
		entityManager.clear();

		assertEquals(17, entityManager.getMetamodel().getEntities().size());
		ReportContent loaded = entityManager.find(ReportContent.class, draft.getId());
		assertEquals("수정문", loaded.getContent().get("text").asString());
		assertFalse(Hibernate.isInitialized(loaded.getBaseContent()));
		assertEquals("원문", loaded.getBaseContent().getContent().get("text").asString());
		assertNotNull(loaded.getCreatedAt());
		assertNotNull(loaded.getUpdatedAt());
		assertEquals(new BigDecimal("95.25"), entityManager.find(ReportAnalysis.class, analysis.getId()).getTotalScore());
		assertTrue(entityManager.find(ReportParseAttempt.class, parsing.getId()).getExtractionWarnings().isArray());
		ReportResult loadedResult = entityManager.find(ReportResult.class, result.getId());
		assertEquals(new BigDecimal("98.50"), loadedResult.getFinalScore());
		assertEquals(draft.getId(), loadedResult.getContent().getId());
		assertEquals(analysis.getId(), loadedResult.getReportAnalysis().getId());
		assertNotNull(loadedResult.getCreatedAt());
		assertNotNull(entityManager.find(IssueSuggestion.class, suggestion.getId()).getCreatedAt());
		assertNotNull(entityManager.find(IssueEvidence.class, evidence.getId()).getCreatedAt());
		assertNotNull(entityManager.find(MemberAuth.class, auth.getId()).getUpdatedAt());
		assertNotNull(entityManager.find(MemberTerm.class, consent.getId()).getUpdatedAt());
		assertNotNull(entityManager.find(RefreshToken.class, token.getId()));
		assertNotNull(entityManager.find(EmailVerification.class, verification.getId()).getUpdatedAt());
		assertEquals(group.getId(), entityManager.find(Issue.class, issue.getId()).getIssueGroup().getId());
		assertTrue(entityManager.find(IssueGroup.class, group.getId()).isEditable());
		assertNotNull(entityManager.find(ReportAnalysisStep.class, step.getId()).getUpdatedAt());
		assertEquals("누락", entityManager.find(ReportAnalysisAreaResult.class, area.getId()).getResultDetail().get("detail").asString());
		assertEquals(now.plusSeconds(30L * 86400).getEpochSecond(),
			entityManager.find(Report.class, report.getId()).getSourceExpiresAt().getEpochSecond());
		loadedResult.softDelete(now.plusSeconds(4));
		entityManager.flush();
		entityManager.clear();
		assertNull(entityManager.find(ReportResult.class, result.getId()));
		assertEquals(1L, ((Number) entityManager.createNativeQuery(
			"select count(*) from report_result where id = :id and deleted_at is not null")
			.setParameter("id", result.getId()).getSingleResult()).longValue());
		assertNotNull(entityManager.find(ReportAnalysis.class, analysis.getId()));
		assertNotNull(entityManager.find(ReportContent.class, draft.getId()));
	}

	@Test
	void databaseRejectsTwoCurrentBodiesButAllowsHistoricalBodies() {
		Report report = newReport();
		ReportContent first = persist(ReportContent.create(report, JSON.objectNode()));
		first.adoptAsCurrent();
		entityManager.flush();
		ReportContent second = persist(ReportContent.create(report, JSON.objectNode()));
		entityManager.flush();
		second.adoptAsCurrent();
		assertThrows(PersistenceException.class, entityManager::flush);
	}

	@Test
	void databaseAllowsRetriesButRejectsTwoSuccessfulAnalyses() {
		ReportContent content = persist(ReportContent.create(newReport(), JSON.objectNode()));
		content.freeze();
		Instant now = Instant.now();
		ReportAnalysis first = persist(ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1"));
		first.start(now);
		first.succeed(new BigDecimal("90.00"), now);
		entityManager.flush();
		ReportAnalysis retry = persist(ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1"));
		entityManager.flush();
		retry.start(now);
		retry.succeed(new BigDecimal("91.00"), now);
		assertThrows(PersistenceException.class, entityManager::flush);
	}

	@Test
	void databaseRejectsDuplicateStep() {
		ReportContent content = persist(ReportContent.create(newReport(), JSON.objectNode()));
		content.freeze();
		ReportAnalysis analysis = persist(ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1"));
		persist(ReportAnalysisStep.create(AnalysisStepCode.STRUCTURE, analysis));
		entityManager.flush();
		assertThrows(PersistenceException.class, () -> {
			persist(ReportAnalysisStep.create(AnalysisStepCode.STRUCTURE, analysis));
			entityManager.flush();
		});
	}

	@Test
	void databaseRejectsMissingForeignKey() {
		assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
			"insert into report_content(report_id, content, status, is_current, revision) values (-1, '{}'::jsonb, 'DRAFT', false, 1)"
		).executeUpdate());
	}

	@Test
	void auditingUpdatesModificationTimeWithoutReplacingCreationTime() {
		ReportContent content = persist(ReportContent.create(newReport(), JSON.objectNode()));
		entityManager.flush();
		Instant createdAt = content.getCreatedAt();
		Instant updatedAt = content.getUpdatedAt();
		content.replaceContent(JSON.objectNode().put("text", "updated"));
		entityManager.flush();
		assertEquals(createdAt, content.getCreatedAt());
		assertTrue(content.getUpdatedAt().isAfter(updatedAt));
	}


	@Test
	void databaseRejectsTwoSuggestionsForSameGroup() {
		IssueGroup group = newGroup();
		persist(IssueSuggestion.create(group, JSON.arrayNode().add("first"), SuggestionGenerationType.RULE));
		entityManager.flush();
		assertThrows(PersistenceException.class, () -> {
			persist(IssueSuggestion.create(group, JSON.arrayNode().add("second"), SuggestionGenerationType.LLM));
			entityManager.flush();
		});
	}

	@Test
	void replacingSuggestionPersistsNewValuesUnderSameId() {
		IssueGroup group = newGroup();
		IssueSuggestion suggestion = persist(IssueSuggestion.create(group,
			JSON.arrayNode().add("original"), SuggestionGenerationType.RULE));
		entityManager.flush();
		Long suggestionId = suggestion.getId();
		Long groupId = group.getId();
		entityManager.clear();
		entityManager.find(IssueSuggestion.class, suggestionId).replaceSuggestion(
			JSON.arrayNode().add("replacement"), SuggestionGenerationType.LLM);
		entityManager.flush();
		entityManager.clear();
		IssueSuggestion reloaded = entityManager.find(IssueSuggestion.class, suggestionId);
		assertEquals(suggestionId, reloaded.getId());
		assertEquals(groupId, reloaded.getIssueGroup().getId());
		assertEquals(JSON.arrayNode().add("replacement"), reloaded.getChanges());
		assertEquals(SuggestionGenerationType.LLM, reloaded.getGenerationType());
		assertEquals(1L, entityManager.createQuery(
			"select count(s) from IssueSuggestion s where s.issueGroup.id = :groupId", Long.class)
			.setParameter("groupId", groupId).getSingleResult());
	}

	private IssueGroup newGroup() {
		ReportContent content = persist(ReportContent.create(newReport(), JSON.objectNode()));
		content.freeze();
		ReportAnalysis analysis = persist(ReportAnalysis.request(content, UUID.randomUUID(), "v1", "v1"));
		return persist(IssueGroup.forTarget(analysis, IssueScope.SENTENCE, "sentence-1"));
	}

	private Report newReport() {
		Member member = persist(Member.createLocal(UUID.randomUUID() + "@example.com", "테스트", "hash"));
		return persist(Report.createFromText(member, ReportType.PLAN, "보고서"));
	}

	private <T> T persist(T entity) {
		entityManager.persist(entity);
		return entity;
	}
}
