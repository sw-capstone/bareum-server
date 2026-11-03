package com.bareum.server.domain.report.body;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import kr.pe.bab2min.Kiwi;
import lombok.Getter;

/**
 * 테스트 클래스 실행 전에 Kiwi를 한 번 초기화해 공유하고,
 * 모든 테스트가 끝나면 사용한 네이티브 자원을 해제하는 JUnit 확장 클래스.
 */
final class KiwiFixture implements BeforeAllCallback, AfterAllCallback {
	@Getter
	private Kiwi kiwi;

	@Override
	public void beforeAll(ExtensionContext context) throws Exception {
		kiwi = Kiwi.init(System.getProperty("kiwi.model.path"), 1);
	}

	@Override
	public void afterAll(ExtensionContext context) throws Exception {
		if (kiwi != null) {
			kiwi.close();
		}
	}
}
