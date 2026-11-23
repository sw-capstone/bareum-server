package com.bareum.server.domain.report.entity;

import java.time.Instant;
import java.util.Objects;

import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 업로드한 보고서의 소유자, 문서 유형과 삭제 상태를 관리한다. */
@Entity
@Table(name = "report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Enumerated(EnumType.STRING)
	@Column(name = "report_type", nullable = false, length = 30)
	private ReportType reportType;

	@Column(name = "intake_cancelled_at")
	private Instant intakeCancelledAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	private Report(Member member, ReportType reportType) {
		this.member = Objects.requireNonNull(member, "member");
		this.reportType = Objects.requireNonNull(reportType, "reportType");
	}

	public static Report create(Member member, ReportType reportType) {
		return new Report(member, reportType);
	}

	public void cancelIntake(Instant cancelledAt) {
		if (intakeCancelledAt == null) {
			softDelete(cancelledAt);
			intakeCancelledAt = cancelledAt;
		}
	}

	public void softDelete(Instant deletedAt) {
		Objects.requireNonNull(deletedAt, "deletedAt");
		if (this.deletedAt == null) {
			this.deletedAt = deletedAt;
		}
	}

	public void requireActiveIntake() {
		if (intakeCancelledAt != null) {
			throw new ReportException(ReportErrorCode.INTAKE_CANCELLED);
		}
		if (deletedAt != null) {
			throw new ReportException(ReportErrorCode.REPORT_NOT_FOUND);
		}
	}
}
