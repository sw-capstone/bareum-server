package com.bareum.server.domain.report.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.enums.ParseStatus;
import com.bareum.server.domain.report.enums.ReportIntakeStatus;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.ReportIntakePolicy;
import com.bareum.server.domain.report.source.UploadedReportFile;
import com.bareum.server.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 멱등 키별 접수 상태와 재사용할 응답, 원본 정리 정보를 저장한다. */
@Entity
@Table(name = "report_intake_request",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_report_intake_member_request_key",
		columnNames = {
			"member_id", "request_key"
		}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportIntakeRequest extends BaseTimeEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(name = "request_key", nullable = false)
	private UUID requestKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64)
	private String requestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private ReportIntakeStatus status;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "report_id")
	private Report report;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parse_attempt_id")
	private ReportParseAttempt parseAttempt;

	@Column(name = "source_object_key", columnDefinition = "text")
	private String sourceObjectKey;

	@Column(name = "source_version_id", columnDefinition = "text")
	private String sourceVersionId;

	@Column(name = "source_cleanup_after")
	private Instant sourceCleanupAfter;

	@Column(name = "source_deleted_at")
	private Instant sourceDeletedAt;

	@Column(name = "response_status")
	private Integer responseStatus;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "response_body", columnDefinition = "jsonb")
	private JsonNode responseBody;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	private ReportIntakeRequest(Member member, UUID requestKey, String fingerprint, Instant requestedAt) {
		this.member = Objects.requireNonNull(member, "member");
		this.requestKey = Objects.requireNonNull(requestKey, "requestKey");
		this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
		if (fingerprint == null || !fingerprint.matches("[a-fA-F0-9]{64}")) {
			throw new ReportException(ReportErrorCode.INVALID_INTAKE_RESULT);
		}
		requestFingerprint = fingerprint.toLowerCase(Locale.ROOT);
		status = ReportIntakeStatus.PROCESSING;
	}

	public static ReportIntakeRequest reserve(Member member, UUID key, String fingerprint, Instant requestedAt) {
		return new ReportIntakeRequest(member, key, fingerprint, requestedAt);
	}

	public void requireMatchingFingerprint(String fingerprint) {
		if (!requestFingerprint.equalsIgnoreCase(fingerprint)) {
			throw new ReportException(ReportErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		}
	}

	public void assignSourceKey(String objectKey) {
		requireProcessing();
		if (objectKey == null || objectKey.isBlank()
				|| (sourceObjectKey != null && !sourceObjectKey.equals(objectKey))) {
			throw new ReportException(ReportErrorCode.INTAKE_REPORT_MISMATCH);
		}
		sourceObjectKey = objectKey;
	}

	public void recordUploadedSource(UploadedReportFile source) {
		requireProcessing();
		if (!Objects.equals(sourceObjectKey, source.objectKey())) {
			throw new ReportException(ReportErrorCode.INTAKE_REPORT_MISMATCH);
		}
		sourceVersionId = source.versionId();
	}

	public void attachAttempt(ReportParseAttempt attempt) {
		requireProcessing();
		Report owner = Objects.requireNonNull(attempt, "attempt").getReport();
		owner.requireActiveIntake();
		Member ownerMember = owner.getMember();
		boolean sameMember = member == ownerMember
				|| (member.getId() != null && member.getId().equals(ownerMember.getId()));
		boolean sameReport = report == null || report == owner
				|| (report.getId() != null && report.getId().equals(owner.getId()));
		if (!sameMember || !sameReport || sourceCleanupAfter != null
				|| !Objects.equals(sourceObjectKey, owner.getSourceObjectKey())
				|| !Objects.equals(sourceVersionId, owner.getSourceVersionId())) {
			throw new ReportException(ReportErrorCode.INTAKE_REPORT_MISMATCH);
		}
		report = owner;
		parseAttempt = attempt;
	}

	public void complete(int httpStatus, JsonNode body, Instant finishedAt) {
		requireProcessing();
		boolean succeeded = httpStatus == 201;
		if ((!succeeded && (httpStatus < 400 || httpStatus > 599)) || body == null || !body.isObject()
				|| finishedAt == null || finishedAt.isBefore(requestedAt)
				|| (succeeded && (parseAttempt == null || parseAttempt.getProcessStatus() != ParseStatus.SUCCEEDED))) {
			throw new ReportException(ReportErrorCode.INVALID_INTAKE_RESULT);
		}
		responseStatus = httpStatus;
		responseBody = body.deepCopy();
		completedAt = finishedAt;
		expiresAt = finishedAt.plus(ReportIntakePolicy.IDEMPOTENCY_RETENTION);
		status = succeeded ? ReportIntakeStatus.SUCCEEDED : ReportIntakeStatus.FAILED;
	}

	public JsonNode getResponseBody() {
		return responseBody == null ? null : responseBody.deepCopy();
	}

	public void requireReplayable() {
		if (report != null) {
			report.requireActiveIntake();
		}
		if (status == ReportIntakeStatus.PROCESSING) {
			throw new ReportException(ReportErrorCode.INTAKE_IN_PROGRESS);
		}
	}

	public void scheduleOrphanCleanup(Instant cleanupAfter) {
		if (status != ReportIntakeStatus.FAILED || report != null || sourceObjectKey == null || cleanupAfter == null) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
		if (sourceDeletedAt == null && (sourceCleanupAfter == null || cleanupAfter.isAfter(sourceCleanupAfter))) {
			sourceCleanupAfter = cleanupAfter;
		}
	}

	public void markOrphanDeleted(Instant deletedAt) {
		if (report != null || sourceCleanupAfter == null || deletedAt == null
				|| deletedAt.isBefore(sourceCleanupAfter)) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
		if (sourceDeletedAt == null) {
			sourceDeletedAt = deletedAt;
		}
	}

	private void requireProcessing() {
		if (status != ReportIntakeStatus.PROCESSING) {
			throw new ReportException(ReportErrorCode.INVALID_INTAKE_STATE);
		}
	}
}
