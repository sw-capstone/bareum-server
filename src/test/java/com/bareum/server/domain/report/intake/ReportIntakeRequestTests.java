package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bareum.server.domain.report.entity.ReportIntakeRequest;
import com.bareum.server.domain.report.entity.ReportParseAttempt;
import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.enums.ReportIntakeStatus;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import tools.jackson.databind.json.JsonMapper;

/** 접수 상태 전이와 응답 재사용, 보관 기한과 원본 정리 조건을 검증한다. */
class ReportIntakeRequestTests {
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void processingRequestsCannotReplayAndDifferentFingerprintsConflict() {
		var request = IntakeFixtures.request(IntakeFixtures.member());
		assertEquals(ReportErrorCode.INTAKE_IN_PROGRESS,
				assertThrows(ReportException.class, request::requireReplayable).getBaseErrorCode());
		assertDoesNotThrow(() -> request.requireMatchingFingerprint("A".repeat(64)));
		assertEquals(ReportErrorCode.IDEMPOTENCY_KEY_CONFLICT,
				assertThrows(ReportException.class, () -> request.requireMatchingFingerprint("b".repeat(64)))
						.getBaseErrorCode());
	}

	@Test
	void terminalFailureStoresAnIndependentResponseForTwentyFourHours() {
		var request = IntakeFixtures.request(IntakeFixtures.member());
		var body = mapper.createObjectNode().put("code", "REPORT-0031");
		var completed = IntakeFixtures.NOW.plusSeconds(320);
		request.complete(504, body, completed);
		body.put("code", "changed");
		((tools.jackson.databind.node.ObjectNode)request.getResponseBody()).put("code", "also changed");
		assertEquals("REPORT-0031", request.getResponseBody().get("code").asString());
		assertEquals(ReportIntakeStatus.FAILED, request.getStatus());
		assertEquals(completed.plus(Duration.ofHours(24)), request.getExpiresAt());
		assertDoesNotThrow(request::requireReplayable);
		assertThrows(ReportException.class, () -> request.complete(504, body, completed.plusSeconds(1)));
	}

	@ParameterizedTest
	@ValueSource(ints = {200, 202, 204, 302, 600})
	void invalidTerminalHttpStatusesLeaveRequestProcessing(int status) {
		var request = IntakeFixtures.request(IntakeFixtures.member());
		assertThrows(ReportException.class,
				() -> request.complete(status, mapper.createObjectNode(), IntakeFixtures.NOW));
		assertEquals(ReportIntakeStatus.PROCESSING, request.getStatus());
		assertNull(request.getExpiresAt());
	}

	@Test
	void malformedBodyOrBackwardsCompletionCannotBeRecorded() {
		var request = IntakeFixtures.request(IntakeFixtures.member());
		assertThrows(ReportException.class, () -> request.complete(500, mapper.createArrayNode(), IntakeFixtures.NOW));
		assertThrows(ReportException.class,
				() -> request.complete(500, mapper.createObjectNode(), IntakeFixtures.NOW.minusSeconds(1)));
		assertThrows(ReportException.class, () -> request.complete(201, mapper.createObjectNode(), IntakeFixtures.NOW));
	}

	@Test
	void requestBindsOnlyToItsOwnMemberAndOriginal() {
		var member = IntakeFixtures.member();
		var request = IntakeFixtures.request(member);
		request.assignSourceKey("reports/planned.pdf");
		var otherReport = IntakeFixtures.report(IntakeFixtures.member());
		assertThrows(ReportException.class, () -> request.attachAttempt(ReportParseAttempt.request(otherReport)));
		assertThrows(ReportException.class, () -> request.assignSourceKey("reports/replacement.pdf"));
		assertNull(request.getReport());
	}

	@Test
	void successUsesTheExistingAttemptAndCancellationPreventsReplay() throws Exception {
		var member = IntakeFixtures.member();
		var source = IntakeFixtures.source("reports/planned.pdf");
		var report = com.bareum.server.domain.report.entity.Report.createFromFile(member,
				com.bareum.server.domain.report.enums.ReportType.PLAN, source);
		var request = IntakeFixtures.request(member);
		request.assignSourceKey(source.objectKey());
		request.recordUploadedSource(source);
		var attempt = ReportParseAttempt.request(report);
		request.attachAttempt(attempt);
		attempt.start(IntakeFixtures.NOW);
		attempt.complete(IntakeFixtures.content(report), ExtractionQuality.PARTIAL, mapper.createArrayNode(),
				IntakeFixtures.NOW.plusSeconds(1));
		request.complete(201, mapper.createObjectNode().put("parseStatus", "SUCCEEDED"),
				IntakeFixtures.NOW.plusSeconds(2));
		assertEquals(ReportIntakeStatus.SUCCEEDED, request.getStatus());
		assertDoesNotThrow(request::requireReplayable);
		report.cancelIntake(IntakeFixtures.NOW.plusSeconds(3));
		assertEquals(ReportErrorCode.INTAKE_CANCELLED,
				assertThrows(ReportException.class, request::requireReplayable).getBaseErrorCode());
	}

	@Test
	void orphanCleanupRetainsTheObjectUntilDeletionCanSafelyFinish() {
		ReportIntakeRequest request = IntakeFixtures.request(IntakeFixtures.member());
		request.assignSourceKey("reports/orphan.pdf");
		assertThrows(ReportException.class, () -> request.scheduleOrphanCleanup(IntakeFixtures.NOW));
		request.complete(500, mapper.createObjectNode(), IntakeFixtures.NOW);
		request.scheduleOrphanCleanup(IntakeFixtures.NOW.plusSeconds(10));
		assertThrows(ReportException.class, () -> request.markOrphanDeleted(IntakeFixtures.NOW));
		assertNull(request.getSourceDeletedAt());
		request.markOrphanDeleted(IntakeFixtures.NOW.plusSeconds(11));
		assertEquals(IntakeFixtures.NOW.plusSeconds(11), request.getSourceDeletedAt());
	}
}
