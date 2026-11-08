package com.bareum.server.domain.report.service.query;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.report.intake.ReportIntakeResult;
import com.bareum.server.domain.report.repository.ReportIntakeRequestRepository;

import lombok.RequiredArgsConstructor;

/** 동일한 접수 요청에 반환할 저장된 결과를 읽기 전용 트랜잭션으로 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportIntakeQueryService {
	private final ReportIntakeRequestRepository requests;

	public Optional<ReportIntakeResult> findReplay(long memberId, UUID key, String fingerprint) {
		return requests.findByMemberIdAndRequestKey(memberId, key).map(request -> {
			request.requireMatchingFingerprint(fingerprint);
			request.requireReplayable();
			return new ReportIntakeResult(request.getResponseStatus(), request.getResponseBody());
		});
	}
}
