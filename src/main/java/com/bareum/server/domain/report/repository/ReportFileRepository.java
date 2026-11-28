package com.bareum.server.domain.report.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.bareum.server.domain.report.entity.ReportFile;
import com.bareum.server.domain.report.enums.ReportFileRole;

import jakarta.persistence.LockModeType;

public interface ReportFileRepository extends JpaRepository<ReportFile, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select f from ReportFile f where f.id = :id")
	Optional<ReportFile> findByIdForUpdate(Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<ReportFile> findByReportIdOrderById(Long reportId);

	Optional<ReportFile> findByReportIdAndRole(Long reportId, ReportFileRole role);

	Optional<ReportFile> findByReportIdAndObjectKeyAndDeletedAtIsNull(Long reportId, String objectKey);

	@Query(value = """
			SELECT f.id FROM report_file f
			WHERE f.s3_deleted_at IS NULL AND f.cleanup_after <= :now AND f.id > :afterId
			  AND NOT EXISTS (SELECT 1 FROM report_intake_request i
			      WHERE i.report_id = f.report_id AND i.status = 'PROCESSING')
			  AND NOT EXISTS (SELECT 1 FROM report_parse_attempt a
			      WHERE a.report_id = f.report_id AND a.process_status IN ('PENDING', 'PROCESSING'))
			ORDER BY f.id LIMIT :batchSize
			""", nativeQuery = true)
	List<Long> findCleanupIds(Instant now, long afterId, int batchSize);
}
