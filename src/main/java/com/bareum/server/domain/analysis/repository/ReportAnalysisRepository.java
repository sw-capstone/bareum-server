package com.bareum.server.domain.analysis.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;

import jakarta.persistence.LockModeType;

public interface ReportAnalysisRepository extends Repository<ReportAnalysis, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select a from ReportAnalysis a
			join a.content c join c.report upload
			where a.id = :analysisId and upload.member.id = :memberId and upload.deletedAt is null
			""")
	Optional<ReportAnalysis> findOwnedForUpdate(long memberId, long analysisId);
}
