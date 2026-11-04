package com.bareum.server.domain.report.repository;

import java.time.Instant;
import java.util.List;
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
	@Query("select r from Report r where r.id = :id")
	Optional<Report> findByIdForUpdate(Long id);

	// 진행 중 접수와 파싱을 제외하고 삭제 예약이나 보관 만료가 된 보고서 원본을 나눠 조회한다.
	@Query(
		value = """
				SELECT r.id FROM report r
				WHERE r.input_method = 'FILE' AND r.source_deleted_at IS NULL AND r.id > :afterId
				  AND ((r.source_cleanup_after IS NOT NULL AND r.source_cleanup_after <= :now)
				       OR (r.source_cleanup_after IS NULL AND r.source_expires_at <= :now))
				  AND NOT EXISTS (SELECT 1 FROM report_intake_request i WHERE i.report_id = r.id AND i.status = 'PROCESSING')
				  AND NOT EXISTS (SELECT 1 FROM report_parse_attempt a WHERE a.report_id = r.id AND a.process_status IN ('PENDING', 'PROCESSING'))
				ORDER BY r.id LIMIT :batchSize
				""",
		nativeQuery = true)
	List<Long> findSourceCleanupIds(Instant now, long afterId, int batchSize);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Report r where r.id = :id and r.member.id = :memberId")
	Optional<Report> findOwnedForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);

	@Query("select count(a) > 0 from ReportAnalysis a where a.content.report.id = :reportId")
	boolean hasAnalyses(@Param("reportId") Long reportId);
}
