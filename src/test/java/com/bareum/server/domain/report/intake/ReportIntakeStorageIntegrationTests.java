package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.dto.response.ReportUploadResponse;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportIntakeRequest;
import com.bareum.server.domain.report.entity.ReportParseAttempt;
import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.job.ReportIntakeRetentionJob;
import com.bareum.server.domain.report.repository.ReportIntakeRequestRepository;
import com.bareum.server.domain.report.service.command.ReportIntakeCommandService;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

/** 실제 DB에서 접수 기록의 저장 제약과 만료 기록 정리를 검증한다. */
@SpringBootTest
@Transactional
class ReportIntakeStorageIntegrationTests {
	@Autowired
	private EntityManager entities;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ReportIntakeRequestRepository requests;
	@Autowired
	private ReportIntakeCommandService commands;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void requestKeysAreUniquePerMemberButCanBeUsedByDifferentMembers() {
		Member first = persistMember();
		Member second = persistMember();
		UUID key = UUID.randomUUID();
		entities.persist(ReportIntakeRequest.reserve(first, key, IntakeFixtures.HASH, IntakeFixtures.NOW));
		entities.persist(ReportIntakeRequest.reserve(second, key, IntakeFixtures.HASH, IntakeFixtures.NOW));
		entities.flush();
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM report_intake_request WHERE request_key = ?",
				Integer.class, key));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO report_intake_request(member_id,request_key,request_fingerprint,status,requested_at)
				VALUES(?,?,?,'PROCESSING',now())
				""", first.getId(), key, IntakeFixtures.HASH));
	}

	@Test
	void expandedErrorColumnAndSourceIdentitySurviveReload() {
		Report report = persistReport(persistMember());
		var attempt = ReportParseAttempt.request(report);
		attempt.start(IntakeFixtures.NOW);
		attempt.fail("SOURCE_DOWNLOAD_TEMPORARY_FAILURE", IntakeFixtures.NOW.plusSeconds(1));
		entities.persist(attempt);
		entities.flush();
		Long id = attempt.getId();
		entities.clear();
		var reloaded = entities.find(ReportParseAttempt.class, id);
		assertEquals("SOURCE_DOWNLOAD_TEMPORARY_FAILURE", reloaded.getErrorCode());
		assertEquals(IntakeFixtures.HASH, reloaded.getReport().getSourceSha256());
		assertEquals("version-1", reloaded.getReport().getSourceVersionId());
	}

	@Test
	void completedResponseAndExpiryAreStoredAsJsonObject() {
		var member = persistMember();
		var request = IntakeFixtures.request(member);
		request.complete(504, mapper.createObjectNode().put("code", "REPORT-0031"), IntakeFixtures.NOW.plusSeconds(1));
		entities.persist(request);
		entities.flush();
		Long id = request.getId();
		entities.clear();
		var loaded = requests.findForUpdate(member.getId(), request.getRequestKey()).orElseThrow();
		assertEquals(id, loaded.getId());
		assertEquals(504, loaded.getResponseStatus());
		assertEquals("REPORT-0031", loaded.getResponseBody().get("code").asString());
		assertEquals("object", jdbc.queryForObject(
				"SELECT jsonb_typeof(response_body) FROM report_intake_request WHERE id = ?", String.class, id));
	}

	@Test
	void successfulPartialIntakeReloadsTheSameContentAndAttemptIds() throws Exception {
		Member member = persistMember();
		Report report = persistReport(member);
		var content = IntakeFixtures.content(report);
		entities.persist(content);
		var attempt = ReportParseAttempt.request(report);
		attempt.start(IntakeFixtures.NOW);
		attempt.complete(content, ExtractionQuality.PARTIAL, mapper.createArrayNode(),
				IntakeFixtures.NOW.plusSeconds(1));
		entities.persist(attempt);
		var request = IntakeFixtures.request(member);
		request.assignSourceKey(report.getSourceObjectKey());
		request.recordUploadedSource(IntakeFixtures.source(report.getSourceObjectKey()));
		request.attachAttempt(attempt);
		request.complete(201, mapper.valueToTree(ReportUploadResponse.from(attempt)),
				IntakeFixtures.NOW.plusSeconds(2));
		entities.persist(request);
		entities.flush();
		Long requestId = request.getId();
		Long attemptId = attempt.getId();
		Long contentId = content.getId();
		entities.clear();
		var loaded = entities.find(ReportIntakeRequest.class, requestId);
		assertEquals(attemptId, loaded.getParseAttempt().getId());
		assertEquals(contentId, loaded.getParseAttempt().getProducedContent().getId());
		assertEquals("PARTIAL", loaded.getResponseBody().get("extractionQuality").asString());
		assertEquals(contentId.longValue(), loaded.getResponseBody().get("contentId").asLong());
	}

	@Test
	void databaseRejectsAnAttemptPairedWithAnotherReport() {
		Member member = persistMember();
		Report report = persistReport(member);
		Report otherReport = persistReport(member);
		var attempt = ReportParseAttempt.request(report);
		entities.persist(attempt);
		var request = IntakeFixtures.request(member);
		request.assignSourceKey(report.getSourceObjectKey());
		request.recordUploadedSource(IntakeFixtures.source(report.getSourceObjectKey()));
		request.attachAttempt(attempt);
		entities.persist(request);
		entities.flush();
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"UPDATE report_intake_request SET report_id = ? WHERE id = ?", otherReport.getId(), request.getId()));
	}

	@Test
	void retentionJobUsesCompletionTimeAndPreservesProcessingAndUncollectedOrphans() {
		Member member = persistMember();
		var expired = terminalRequest(member);
		var processing = IntakeFixtures.request(member);
		var orphan = terminalRequest(member);
		// Even a crash before cleanup scheduling must not lose the planned S3 key.
		var planned = IntakeFixtures.request(member);
		planned.assignSourceKey("reports/uncertain-upload.pdf");
		planned.complete(500, mapper.createObjectNode(), IntakeFixtures.NOW.plusSeconds(1));
		var collecting = IntakeFixtures.request(member);
		collecting.assignSourceKey("reports/orphan.pdf");
		collecting.complete(500, mapper.createObjectNode(), IntakeFixtures.NOW.plusSeconds(1));
		collecting.scheduleOrphanCleanup(IntakeFixtures.NOW.plusSeconds(2));
		for (var request : new ReportIntakeRequest[] {expired, processing, orphan, planned, collecting}) {
			entities.persist(request);
		}
		entities.flush();
		var beforeExpiry = new ReportIntakeRetentionJob(commands,
				Clock.fixed(IntakeFixtures.NOW.plusSeconds(86_400), ZoneOffset.UTC));
		assertEquals(0, beforeExpiry.purgeExpiredRequests());
		var atExpiry = new ReportIntakeRetentionJob(commands,
				Clock.fixed(IntakeFixtures.NOW.plusSeconds(86_401), ZoneOffset.UTC));
		assertEquals(2, atExpiry.purgeExpiredRequests());
		assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM report_intake_request WHERE member_id = ?",
				Integer.class, member.getId()));
		collecting.markOrphanDeleted(IntakeFixtures.NOW.plusSeconds(3));
		entities.flush();
		assertEquals(1, atExpiry.purgeExpiredRequests());
	}

	@Test
	void retentionPreservesAttachedReportsWithPendingSourceDeletion() {
		Member member = persistMember();
		Report report = persistReport(member);
		var attempt = ReportParseAttempt.request(report);
		attempt.start(IntakeFixtures.NOW);
		attempt.fail("PARSER_FAILED", IntakeFixtures.NOW.plusSeconds(1));
		entities.persist(attempt);
		var request = IntakeFixtures.request(member);
		request.assignSourceKey(report.getSourceObjectKey());
		request.recordUploadedSource(IntakeFixtures.source(report.getSourceObjectKey()));
		request.attachAttempt(attempt);
		request.complete(500, mapper.createObjectNode(), IntakeFixtures.NOW.plusSeconds(2));
		report.scheduleSourceCleanup(IntakeFixtures.NOW.plusSeconds(2));
		entities.persist(request);
		entities.flush();
		var now = IntakeFixtures.NOW.plusSeconds(90_000);
		var job = new ReportIntakeRetentionJob(commands, Clock.fixed(now, ZoneOffset.UTC));
		assertEquals(0, job.purgeExpiredRequests());
		report.markSourceDeleted(now);
		entities.flush();
		assertEquals(1, job.purgeExpiredRequests());
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM report WHERE id = ?", Integer.class, report.getId()));
	}

	@Test
	void databaseRejectsTwoActiveAttemptsForTheSameReport() {
		var report = persistReport(persistMember());
		entities.persist(ReportParseAttempt.request(report));
		entities.flush();
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"INSERT INTO report_parse_attempt(report_id) VALUES (?)", report.getId()));
	}

	@Test
	void failedAttemptAllowsANewAttemptWithoutReplacingHistory() {
		var report = persistReport(persistMember());
		var first = ReportParseAttempt.request(report);
		first.start(IntakeFixtures.NOW);
		first.fail("CAPACITY_EXCEEDED", IntakeFixtures.NOW.plusSeconds(1));
		entities.persist(first);
		entities.persist(ReportParseAttempt.request(report));
		entities.flush();
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM report_parse_attempt WHERE report_id = ?",
				Integer.class, report.getId()));
	}

	private Member persistMember() {
		Member member = IntakeFixtures.member();
		entities.persist(member);
		return member;
	}

	private Report persistReport(Member member) {
		Report report = IntakeFixtures.report(member);
		entities.persist(report);
		return report;
	}

	private ReportIntakeRequest terminalRequest(Member member) {
		var request = IntakeFixtures.request(member);
		request.complete(500, mapper.createObjectNode(), IntakeFixtures.NOW.plusSeconds(1));
		return request;
	}
}
