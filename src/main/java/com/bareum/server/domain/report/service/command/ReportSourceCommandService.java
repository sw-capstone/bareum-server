package com.bareum.server.domain.report.service.command;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.report.enums.InputMethod;
import com.bareum.server.domain.report.enums.ParseStatus;
import com.bareum.server.domain.report.enums.ReportIntakeStatus;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.repository.ReportIntakeRequestRepository;
import com.bareum.server.domain.report.repository.ReportParseAttemptRepository;
import com.bareum.server.domain.report.repository.ReportRepository;
import com.bareum.server.domain.report.source.SourceCleanupTarget;

/** 파일 접수 취소와 원본 정리 대상 확정, 버전 복구와 삭제 완료 기록을 저장한다. */
@Service
@Transactional
public class ReportSourceCommandService {
	private final ReportRepository reports;
	private final ReportParseAttemptRepository attempts;
	private final Clock clock;
	private final ReportIntakeRequestRepository requests;

	public ReportSourceCommandService(
		ReportRepository reports,
		ReportParseAttemptRepository attempts,
		Clock clock,
		ReportIntakeRequestRepository requests
	) {
		this.reports = reports;
		this.attempts = attempts;
		this.clock = clock;
		this.requests = requests;
	}

	public boolean request(long memberId, long reportId) {
		var report = reports.findOwnedForUpdate(reportId, memberId)
				.orElseThrow(
						() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
		if (report.getInputMethod() != InputMethod.FILE) {
			throw new ReportException(ReportErrorCode.REPORT_NOT_FOUND);
		}
		if (reports.hasAnalyses(reportId)
				|| requests.existsByReportIdAndStatus(reportId, ReportIntakeStatus.PROCESSING)
				|| attempts.existsByReportIdAndProcessStatusIn(reportId,
						List.of(ParseStatus.PENDING, ParseStatus.PROCESSING))) {
			throw new ReportException(ReportErrorCode.SOURCE_IN_USE);
		}
		report.cancelIntake(clock.instant());
		return report.getSourceDeletedAt() != null;
	}

	/** 사용 중인 원본을 제외하고 삭제 예약이나 보관 만료를 다시 확인해 보고서 원본의 정리를 확정한다. */
	public Optional<SourceCleanupTarget> prepareReportCleanup(long reportId) {
		return reports.findByIdForUpdate(reportId).filter(report -> {
			var now = clock.instant();
			if (report.getInputMethod() != InputMethod.FILE || report.getSourceDeletedAt() != null
					|| report.getSourceObjectKey() == null
					|| requests.existsByReportIdAndStatus(reportId, ReportIntakeStatus.PROCESSING)
					|| attempts.existsByReportIdAndProcessStatusIn(reportId,
							List.of(ParseStatus.PENDING, ParseStatus.PROCESSING))) {
				return false;
			}
			if (report.getSourceCleanupAfter() != null) {
				return !now.isBefore(report.getSourceCleanupAfter());
			}
			if (report.getSourceExpiresAt() == null || now.isBefore(report.getSourceExpiresAt())) {
				return false;
			}
			report.scheduleSourceCleanup(now);
			return true;
		})
				.map(report -> new SourceCleanupTarget(reportId, false, report.getSourceObjectKey(),
						report.getSourceVersionId()));
	}

	/** 보고서 생성 전에 실패한 접수 중 삭제 유예가 끝났고 아직 정리하지 않은 원본을 확정한다. */
	public Optional<SourceCleanupTarget> prepareOrphanCleanup(long requestId) {
		return requests.findByIdForUpdate(requestId)
				.filter(request -> request.getStatus() == ReportIntakeStatus.FAILED && request.getReport() == null
						&& request.getSourceDeletedAt() == null && request.getSourceCleanupAfter() != null
						&& !clock.instant().isBefore(request.getSourceCleanupAfter()))
				.map(request -> new SourceCleanupTarget(requestId, true, request.getSourceObjectKey(),
						request.getSourceVersionId()));
	}

	/** 정리 대상의 객체 키를 재확인하고 S3에서 찾은 버전을 해당 보고서나 접수 기록에 저장한다. */
	public void recoverVersion(SourceCleanupTarget target, String versionId) {
		if (target.orphan()) {
			var request = requests.findByIdForUpdate(target.id()).orElseThrow();
			requireMatchingKey(target, request.getSourceObjectKey());
			request.recoverSourceVersion(versionId);
		} else {
			var report = reports.findByIdForUpdate(target.id()).orElseThrow();
			requireMatchingKey(target, report.getSourceObjectKey());
			report.recoverSourceVersion(versionId);
		}
	}

	/** 실제 S3 삭제가 끝난 원본의 소유 기록을 잠그고 키를 확인한 뒤 삭제 완료 시각을 저장한다. */
	public void markDeleted(SourceCleanupTarget target) {
		if (target.orphan()) {
			var request = requests.findByIdForUpdate(target.id()).orElseThrow();
			requireMatchingKey(target, request.getSourceObjectKey());
			request.markOrphanDeleted(clock.instant());
		} else {
			var report = reports.findByIdForUpdate(target.id()).orElseThrow();
			requireMatchingKey(target, report.getSourceObjectKey());
			report.markSourceDeleted(clock.instant());
		}
	}

	private void requireMatchingKey(SourceCleanupTarget target, String objectKey) {
		if (!target.objectKey().equals(objectKey)) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
	}
}
