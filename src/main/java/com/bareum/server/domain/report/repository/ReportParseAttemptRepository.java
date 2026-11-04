package com.bareum.server.domain.report.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bareum.server.domain.report.entity.ReportParseAttempt;
import com.bareum.server.domain.report.enums.ParseStatus;

/** 파싱 이력을 저장하고 보고서에 진행 중인 파싱이 있는지 확인한다. */
public interface ReportParseAttemptRepository extends JpaRepository<ReportParseAttempt, Long> {
	long countByReportId(Long reportId);
	boolean existsByReportIdAndProcessStatusIn(Long reportId, Collection<ParseStatus> statuses);
}
