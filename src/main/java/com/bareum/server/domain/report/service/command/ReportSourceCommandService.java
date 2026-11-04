package com.bareum.server.domain.report.service.command;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.report.enums.InputMethod;
import com.bareum.server.domain.report.enums.ParseStatus;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.repository.ReportParseAttemptRepository;
import com.bareum.server.domain.report.repository.ReportRepository;

/** 본인 소유의 미사용 파일 접수를 취소하고 원본 삭제를 예약한다. */
@Service
@Transactional
public class ReportSourceCommandService {
	private final ReportRepository reports;
	private final ReportParseAttemptRepository attempts;
	private final Clock clock;

	public ReportSourceCommandService(
		ReportRepository reports,
		ReportParseAttemptRepository attempts,
		@Qualifier("reportIntakeClock") Clock clock
	) {
		this.reports = reports;
		this.attempts = attempts;
		this.clock = clock;
	}

	public boolean request(long memberId, long reportId) {
		var report = reports.findOwnedForUpdate(reportId, memberId)
				.orElseThrow(
						() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
		if (report.getInputMethod() != InputMethod.FILE) {
			throw new ReportException(ReportErrorCode.REPORT_NOT_FOUND);
		}
		if (reports.hasAnalyses(reportId)
				|| attempts.existsByReportIdAndProcessStatusIn(reportId,
						List.of(ParseStatus.PENDING, ParseStatus.PROCESSING))) {
			throw new ReportException(ReportErrorCode.SOURCE_IN_USE);
		}
		report.cancelIntake(clock.instant());
		return report.getSourceDeletedAt() != null;
	}
}
