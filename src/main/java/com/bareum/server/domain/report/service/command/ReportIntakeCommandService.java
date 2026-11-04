package com.bareum.server.domain.report.service.command;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.report.repository.ReportIntakeRequestRepository;

/** 보관 기한이 지난 접수 요청 기록을 쓰기 트랜잭션으로 삭제한다. */
@Service
@Transactional
public class ReportIntakeCommandService {
	private final ReportIntakeRequestRepository requests;

	public ReportIntakeCommandService(ReportIntakeRequestRepository requests) {
		this.requests = requests;
	}

	public int purgeExpiredRequests(Instant now) {
		return requests.deleteExpiredBatch(now, 1000);
	}
}
