package com.bareum.server.domain.report.service.query;

import org.springframework.stereotype.Service;

import com.bareum.server.domain.report.dto.response.ReportPreviewResponse;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.repository.ReportRepository;
import com.bareum.server.domain.report.repository.ReportRepository.PreviewTarget;
import com.bareum.server.domain.report.source.ReportSourceStore;
import com.bareum.server.domain.report.source.ReportSourceStore.DownloadSource;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReportPreviewQueryService {
	private final ReportRepository reportRepository;
	private final ReportSourceStore reportSourceStore;

	public ReportPreviewResponse findPreview(long memberId, long reportId) {
		PreviewTarget target = reportRepository.findOwnedPreview(reportId, memberId)
				.orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
		if (target.getContentId() == null) {
			throw new ReportException(ReportErrorCode.PREVIEW_NOT_READY);
		}
		if (target.getObjectKey() == null) {
			throw new ReportException(ReportErrorCode.PREVIEW_PDF_NOT_FOUND);
		}
		DownloadSource download = reportSourceStore.presignPreview(target.getObjectKey(), target.getVersionId());
		return new ReportPreviewResponse(reportId, download.url(), download.expiresAt());
	}
}
