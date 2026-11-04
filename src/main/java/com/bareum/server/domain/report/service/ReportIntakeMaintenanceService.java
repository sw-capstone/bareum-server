package com.bareum.server.domain.report.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.report.service.command.ReportIntakeCommandService;
import com.bareum.server.domain.report.service.command.ReportSourceCommandService;
import com.bareum.server.domain.report.service.query.ReportMaintenanceQueryService;
import com.bareum.server.domain.report.source.ReportSourceStore;
import com.bareum.server.domain.report.source.SourceCleanupTarget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 중단 요청을 복구하고 DB 트랜잭션 밖에서 원본 삭제와 실패 재처리를 실행한다. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ReportIntakeMaintenanceService {
	private final ReportMaintenanceQueryService queries;
	private final ReportIntakeCommandService intakes;
	private final ReportSourceCommandService sources;
	private final ReportSourceStore store;
	private final Clock clock;

	public void recoverInterruptedRequests() {
		var now = clock.instant();
		forEachBatch(afterId -> queries.expiredRequests(now, afterId), intakes::recoverExpired);
	}

	/** 삭제 시각이 지난 보고서 원본과 보고서 생성 전에 남은 원본을 각각 나눠 조회해 정리한다. */
	public void deleteSources() {
		var now = clock.instant();
		forEachBatch(afterId -> queries.reportSources(now, afterId), id -> delete(sources.prepareReportCleanup(id)));
		forEachBatch(afterId -> queries.orphanSources(now, afterId), id -> delete(sources.prepareOrphanCleanup(id)));
	}

	/** 누락된 버전을 복구해 S3 원본을 삭제하고 모든 삭제가 성공한 뒤 DB에 완료를 기록한다. */
	private void delete(Optional<SourceCleanupTarget> pending) {
		if (pending.isEmpty()) {
			return;
		}
		var target = pending.get();
		var versions =
				target.versionId() == null ? store.findVersions(target.objectKey()) : List.of(target.versionId());
		if (target.versionId() == null && versions.size() == 1) {
			sources.recoverVersion(target, versions.getFirst());
		}
		// 같은 키의 특정 버전 삭제는 중복 실행해도 안전하며, DB 기록 실패 시에도 다시 실행한다.
		for (String version : versions) {
			store.delete(target.objectKey(), version);
		}
		sources.markDeleted(target);
	}

	/** 마지막 식별자 이후를 계속 조회하며 개별 처리 실패를 기록하고 나머지 대상도 처리한다. */
	private void forEachBatch(LongFunction<List<Long>> find, LongConsumer operation) {
		long afterId = 0;
		while (true) {
			var ids = find.apply(afterId);
			if (ids.isEmpty()) {
				return;
			}
			for (long id : ids) {
				try {
					operation.accept(id);
				} catch (RuntimeException exception) {
					log.warn("Report maintenance failed: recordId={}, failureType={}", id,
							exception.getClass().getSimpleName());
				}
			}
			afterId = ids.getLast();
		}
	}
}
