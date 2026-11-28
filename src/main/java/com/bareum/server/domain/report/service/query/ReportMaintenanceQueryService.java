package com.bareum.server.domain.report.service.query;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.report.config.ReportIntakeProperties;
import com.bareum.server.domain.report.repository.ReportFileRepository;
import com.bareum.server.domain.report.repository.ReportIntakeRequestRepository;

import lombok.RequiredArgsConstructor;

/** 중단된 접수와 삭제할 원본의 식별자를 읽기 전용 트랜잭션으로 나눠 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportMaintenanceQueryService {
	private final ReportIntakeRequestRepository requests;
	private final ReportFileRepository files;
	private final ReportIntakeProperties properties;

	public List<Long> expiredRequests(Instant now, long afterId) {
		return requests.findExpiredProcessingIds(now, afterId, properties.maintenanceBatchSize());
	}

	public List<Long> orphanSources(Instant now, long afterId) {
		return requests.findOrphanCleanupIds(now, afterId, properties.maintenanceBatchSize());
	}

	public List<Long> reportFiles(Instant now, long afterId) {
		return files.findCleanupIds(now, afterId, properties.maintenanceBatchSize());
	}
}
