package com.bareum.server.domain.analysis.repository;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.bareum.server.domain.analysis.entity.AnalysisResultReport;

public interface AnalysisResultReportRepository extends Repository<AnalysisResultReport, Long> {
	String VISIBLE_REPORTS = """
			select result from AnalysisResultReport result
			join fetch result.reportAnalysis a
			join a.content c join c.report upload
			where upload.member.id = :memberId and upload.deletedAt is null
				and a.deletedAt is null
				and a.status = com.bareum.server.domain.analysis.enums.AnalysisStatus.SUCCEEDED
			""";

	@Query(VISIBLE_REPORTS + " order by result.generatedAt desc, result.id desc")
	Slice<AnalysisResultReport> findVisibleByMemberId(long memberId, Pageable pageable);

	@Query(VISIBLE_REPORTS + " and a.id = :analysisId")
	Optional<AnalysisResultReport> findVisibleByAnalysisId(long memberId, long analysisId);
}
