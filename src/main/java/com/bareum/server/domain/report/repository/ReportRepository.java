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

	@Query("""
			select content.id as contentId,
			       coalesce(original.objectKey, converted.objectKey) as objectKey,
			       case when original.id is not null then original.versionId else converted.versionId end as versionId
			from Report r
			left join ReportContent content on content.report = r
			left join ReportFile original on original.report = r and original.role = ORIGINAL
			    and original.format = PDF and original.deletedAt is null and original.s3DeletedAt is null
			left join ReportFile converted on converted.report = r and converted.role = CONVERTED_PDF
			    and converted.format = PDF and converted.deletedAt is null and converted.s3DeletedAt is null
			where r.id = :reportId and r.member.id = :memberId and r.deletedAt is null and r.intakeCancelledAt is null
			""")
	Optional<PreviewTarget> findOwnedPreview(long reportId, long memberId);

	@Query("select count(a) > 0 from ReportAnalysis a where a.content.report.id = :reportId")
	boolean hasAnalyses(@Param("reportId") Long reportId);

	interface PreviewTarget {
		Long getContentId();

		String getObjectKey();

		String getVersionId();
	}
}
