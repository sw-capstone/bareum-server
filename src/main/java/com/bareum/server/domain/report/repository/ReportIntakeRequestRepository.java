package com.bareum.server.domain.report.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bareum.server.domain.report.entity.ReportIntakeRequest;
import com.bareum.server.domain.report.enums.ReportIntakeStatus;

import jakarta.persistence.LockModeType;

/** 접수 요청 기록을 조회하고 저장하며 만료된 기록을 일괄 삭제한다. */
public interface ReportIntakeRequestRepository extends JpaRepository<ReportIntakeRequest, Long> {
	Optional<ReportIntakeRequest> findByMemberIdAndRequestKey(Long memberId, UUID requestKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ReportIntakeRequest r where r.id = :id")
	Optional<ReportIntakeRequest> findByIdForUpdate(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ReportIntakeRequest r where r.member.id = :memberId and r.requestKey = :requestKey")
	Optional<ReportIntakeRequest> findForUpdate(@Param("memberId") Long memberId, @Param("requestKey") UUID key);

	@Query(value = """
			SELECT id FROM report_intake_request
			WHERE status = 'PROCESSING' AND processing_deadline_at <= :now AND id > :afterId
			ORDER BY id LIMIT :batchSize
			""", nativeQuery = true)
	List<Long> findExpiredProcessingIds(Instant now, long afterId, int batchSize);

	@Query(value = """
			SELECT id FROM report_intake_request
			WHERE status = 'FAILED' AND report_id IS NULL AND source_object_key IS NOT NULL
			  AND source_deleted_at IS NULL AND source_cleanup_after <= :now AND id > :afterId
			ORDER BY id LIMIT :batchSize
			""", nativeQuery = true)
	List<Long> findOrphanCleanupIds(Instant now, long afterId, int batchSize);

	boolean existsByReportIdAndStatus(Long reportId, ReportIntakeStatus status);

	@Modifying
	@Query(value = """
			DELETE FROM report_intake_request WHERE id IN (
			    SELECT r.id FROM report_intake_request r
			    WHERE r.status IN ('SUCCEEDED', 'FAILED') AND r.expires_at <= :now
			      AND (r.report_id IS NOT NULL OR r.source_object_key IS NULL OR r.source_deleted_at IS NOT NULL)
			      AND (r.source_cleanup_after IS NULL OR r.source_deleted_at IS NOT NULL)
			      AND NOT EXISTS (
			          SELECT 1 FROM report p WHERE p.id = r.report_id
			            AND p.source_cleanup_after IS NOT NULL AND p.source_deleted_at IS NULL)
			    ORDER BY r.expires_at, r.id LIMIT :batchSize FOR UPDATE OF r SKIP LOCKED
			)
			""", nativeQuery = true)
	int deleteExpiredBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
