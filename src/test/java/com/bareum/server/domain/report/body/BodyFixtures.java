package com.bareum.server.domain.report.body;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 공통 본문 검증 및 PDF 변환 테스트에서 사용하는 JSON 샘플을 읽는 보조 클래스.
 * 파일 내용을 문자열로 반환하며, JSON 파싱이나 검증은 수행하지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BodyFixtures {
	// 지정한 버전과 이름의 공통 본문 예제 JSON 파일을 읽는다.
	static String contract(String version, String name) throws IOException {
		return Files
				.readString(Path.of("packages/contracts/report-content", version, "examples", name + ".example.json"));
	}

	// 지정한 버전의 PDF 파서 응답 샘플을 읽는다. 실제 추출 결과가 아닌 테스트용 데이터다.
	static String parsed(String version) throws IOException {
		return Files.readString(Path.of("src/test/resources/report", "pdf-extraction-" + version + ".json"));
	}
}
