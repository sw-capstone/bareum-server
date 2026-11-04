package com.bareum.server.domain.report.service.query;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.report.intake.ReportIntakePolicy;
import com.bareum.server.domain.report.repository.ReportIntakeRequestRepository;
import com.bareum.server.domain.report.repository.ReportRepository;

import lombok.RequiredArgsConstructor;

/** 중단된 접수와 삭제할 원본의 식별자를 읽기 전용 트랜잭션으로 나눠 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportMaintenanceQueryService {
	private final ReportIntakeRequestRepository requests;
	private final ReportRepository reports;

	public List<Long> expiredRequests(Instant now, long afterId) {
		return requests.findExpiredProcessingIds(now, afterId, ReportIntakePolicy.MAINTENANCE_BATCH_SIZE);
	}

	public List<Long> orphanSources(Instant now, long afterId) {
		return requests.findOrphanCleanupIds(now, afterId, ReportIntakePolicy.MAINTENANCE_BATCH_SIZE);
	}

	public List<Long> reportSources(Instant now, long afterId) {
		return reports.findSourceCleanupIds(now, afterId, ReportIntakePolicy.MAINTENANCE_BATCH_SIZE);
	}
}
