package com.bareum.server.domain.report.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bareum.server.domain.report.entity.Report;

import jakarta.persistence.LockModeType;

/** 보고서의 소유권과 사용 상태를 확인하고 필요한 행 잠금을 획득한다. */
public interface ReportRepository extends JpaRepository<Report, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Report r where r.id = :id and r.member.id = :memberId")
	Optional<Report> findOwnedForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);

	@Query("select count(a) > 0 from ReportAnalysis a where a.content.report.id = :reportId")
	boolean hasAnalyses(@Param("reportId") Long reportId);
}
