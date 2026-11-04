package com.bareum.server.domain.report.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bareum.server.domain.report.entity.ReportIntakeRequest;

import jakarta.persistence.LockModeType;

/** 접수 요청 기록을 조회하고 저장한다. */
public interface ReportIntakeRequestRepository extends JpaRepository<ReportIntakeRequest, Long> {
	Optional<ReportIntakeRequest> findByMemberIdAndRequestKey(Long memberId, UUID requestKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ReportIntakeRequest r where r.id = :id")
	Optional<ReportIntakeRequest> findByIdForUpdate(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ReportIntakeRequest r where r.member.id = :memberId and r.requestKey = :requestKey")
	Optional<ReportIntakeRequest> findForUpdate(@Param("memberId") Long memberId, @Param("requestKey") UUID key);

}
