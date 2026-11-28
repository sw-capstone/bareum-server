package com.bareum.server.domain.report.service.command;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.report.enums.ParseStatus;
import com.bareum.server.domain.report.enums.ReportIntakeStatus;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.repository.ReportFileRepository;
import com.bareum.server.domain.report.repository.ReportIntakeRequestRepository;
import com.bareum.server.domain.report.repository.ReportParseAttemptRepository;
import com.bareum.server.domain.report.repository.ReportRepository;
import com.bareum.server.domain.report.source.SourceCleanupTarget;

import lombok.RequiredArgsConstructor;

/** 접수 취소와 파일 정리 대상 확정, 버전 복구 및 삭제 완료 기록을 저장한다. */
@Service
@Transactional
@RequiredArgsConstructor
public class ReportSourceCommandService {
	private final ReportRepository reports;
	private final ReportFileRepository files;
	private final ReportParseAttemptRepository attempts;
	private final Clock clock;
	private final ReportIntakeRequestRepository requests;

	public boolean request(long memberId, long reportId) {
		var report = reports.findOwnedForUpdate(reportId, memberId)
				.orElseThrow(
						() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
		if (report.getDeletedAt() != null && report.getIntakeCancelledAt() == null) {
			throw new ReportException(ReportErrorCode.REPORT_NOT_FOUND);
		}
		if (reports.hasAnalyses(reportId) || hasActiveIntake(reportId)) {
			throw new ReportException(ReportErrorCode.SOURCE_IN_USE);
		}
		var reportFiles = files.findByReportIdOrderById(reportId);
		if (reportFiles.isEmpty()) {
			throw new ReportException(ReportErrorCode.REPORT_NOT_FOUND);
		}
		var now = clock.instant();
		report.cancelIntake(now);
		reportFiles
				.forEach(file -> file.scheduleDeletion(report.getIntakeCancelledAt(), report.getIntakeCancelledAt()));
		return reportFiles.stream().allMatch(file -> file.getS3DeletedAt() != null);
	}

	/** 사용 중인 보고서를 제외하고 삭제 예정 시각을 다시 확인한다. */
	public Optional<SourceCleanupTarget> prepareFileCleanup(long fileId) {
		return files.findByIdForUpdate(fileId)
				.filter(file -> file.isReadyForCleanup(clock.instant()) && !hasActiveIntake(file.getReport().getId()))
				.map(file -> new SourceCleanupTarget(fileId, false, file.getObjectKey(), file.getVersionId()));
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
			var file = files.findByIdForUpdate(target.id()).orElseThrow();
			requireMatchingKey(target, file.getObjectKey());
			file.recoverVersion(versionId);
		}
	}

	/** 실제 S3 삭제가 끝난 원본의 소유 기록을 잠그고 키를 확인한 뒤 삭제 완료 시각을 저장한다. */
	public void markDeleted(SourceCleanupTarget target) {
		if (target.orphan()) {
			var request = requests.findByIdForUpdate(target.id()).orElseThrow();
			requireMatchingKey(target, request.getSourceObjectKey());
			request.markOrphanDeleted(clock.instant());
		} else {
			var file = files.findByIdForUpdate(target.id()).orElseThrow();
			requireMatchingKey(target, file.getObjectKey());
			file.markS3Deleted(clock.instant());
		}
	}

	private boolean hasActiveIntake(long reportId) {
		return requests.existsByReportIdAndStatus(reportId, ReportIntakeStatus.PROCESSING)
				|| attempts.existsByReportIdAndProcessStatusIn(reportId,
						List.of(ParseStatus.PENDING, ParseStatus.PROCESSING));
	}

	private void requireMatchingKey(SourceCleanupTarget target, String objectKey) {
		if (!target.objectKey().equals(objectKey)) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
	}
}
