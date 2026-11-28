package com.bareum.server.domain.analysis.service.command;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.analysis.exception.AnalysisErrorCode;
import com.bareum.server.domain.analysis.exception.AnalysisException;
import com.bareum.server.domain.analysis.repository.ReportAnalysisRepository;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class AnalysisRecordCommandService {
	private final ReportAnalysisRepository reportAnalysisRepository;
	private final Clock clock;

	public void delete(long memberId, long analysisId) {
		ReportAnalysis analysis = reportAnalysisRepository.findOwnedForUpdate(memberId, analysisId)
				.orElseThrow(() -> new AnalysisException(AnalysisErrorCode.ANALYSIS_NOT_FOUND));
		analysis.softDelete(clock.instant());
	}
}
