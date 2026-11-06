package com.bareum.server.domain.report.intake;

import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;

import com.bareum.server.domain.report.config.ReportIntakeProperties;

/** 현재 Spring 인스턴스에서 S3 저장과 파싱을 수행할 신규 접수 수를 대기 없이 제한한다. */
@Component
public class ReportIntakeLimiter {
	private final Semaphore permits;

	/** 환경설정에 지정한 동시 접수 수만큼 처리 자리를 준비한다. */
	public ReportIntakeLimiter(ReportIntakeProperties properties) {
		permits = new Semaphore(properties.maxConcurrentRequests());
	}

	/** 처리 자리가 있을 때만 즉시 확보하고 없으면 대기하지 않는다. */
	public boolean tryAcquire() {
		return permits.tryAcquire();
	}

	/** 확보한 처리 자리를 접수 종료 후 반환한다. */
	public void release() {
		permits.release();
	}
}
