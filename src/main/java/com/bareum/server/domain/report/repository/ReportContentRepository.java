package com.bareum.server.domain.report.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bareum.server.domain.report.entity.ReportContent;

/** 보고서의 공통 본문을 조회하고 저장한다. */
public interface ReportContentRepository extends JpaRepository<ReportContent, Long> {
}
