package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.ReportBody.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import kr.pe.bab2min.Kiwi;

class TextSegmenterTests {
	@RegisterExtension
	private static final KiwiFixture kiwi = new KiwiFixture();
	private TextSegmenter segmenter;

	@BeforeEach
	void setUp() {
		segmenter = new TextSegmenter(kiwi.getKiwi());
	}

	static Stream<Arguments> cases() {
		return Stream.of(
				Arguments.of("교육을 실시했다. 만족도 조사를 진행했다.", List.of("교육을 실시했다.", "만족도 조사를 진행했다.")),
				Arguments.of("2026. 10. 17. 교육을 실시했다.", List.of("2026. 10. 17. 교육을 실시했다.")),
				Arguments.of("2026. 10. 17.", List.of("2026. 10. 17.")),
				Arguments.of("1. 추진 결과", List.of("1. 추진 결과")),
				Arguments.of("사업비는 3.5억원이다. 집행을 완료했다.", List.of("사업비는 3.5억원이다.", "집행을 완료했다.")),
				Arguments.of("교육을 실시하고 의견을 수렴하여 다음 계획에 반영했다.", List.of("교육을 실시하고 의견을 수렴하여 다음 계획에 반영했다.")),
				Arguments.of("주민 의견 수렴 및 사업계획 반영", List.of("주민 의견 수렴 및 사업계획 반영")),
				Arguments.of("1,000,000원", List.of("1,000,000원")),
				Arguments.of("캐나다", List.of("캐나다")),
				Arguments.of("바다", List.of("바다")),
				Arguments.of("교육을 실시했다", List.of("교육을 실시했다")),
				Arguments.of("예산을 대폭 확대하여 추진함", List.of("예산을 대폭 확대하여 추진함")),
				Arguments.of("지원금 교부 예정임", List.of("지원금 교부 예정임")),
				Arguments.of("교육을 실시했다 만족도 조사를 진행했다", List.of("교육을 실시했다 만족도 조사를 진행했다")),
				Arguments.of("담당자는 “교육을 실시했다.”고 보고했다.", List.of("담당자는 “교육을 실시했다.”고 보고했다.")),
				Arguments.of("담당자는 '교육을 실시했다.'고 보고했다.", List.of("담당자는 '교육을 실시했다.'고 보고했다.")),
				Arguments.of("문구(예: A. B.)를 확인했다. 완료했다.", List.of("문구(예: A. B.)를 확인했다.", "완료했다.")),
				Arguments.of("https://example.org/v1.2 안내를 확인했다.", List.of("https://example.org/v1.2 안내를 확인했다.")),
				Arguments.of("주민 의견 수렴\n및 사업계획 반영", List.of("주민 의견 수렴\n및 사업계획 반영")),
				Arguments.of("교육 완료.  조사 예정.", List.of("교육 완료.", "조사 예정.")),
				Arguments.of("참고 1. 총액인건비제 TF 구성 및 운영", List.of("참고 1. 총액인건비제 TF 구성 및 운영")),
				Arguments.of("Ⅱ. '22년도 세부운영 계획(안)", List.of("Ⅱ. '22년도 세부운영 계획(안)")),
				Arguments.of("'22. 5월~12월", List.of("'22. 5월~12월")),
				Arguments.of("Dr. Kim이 참석했다.", List.of("Dr. Kim이 참석했다.")),
				Arguments.of("교육을 실시했다.조사를 진행했다.", List.of("교육을 실시했다.", "조사를 진행했다."))
		);
	}

	@ParameterizedTest
	@MethodSource("cases")
	void preservesConfirmedBoundaries(String text, List<String> expected) {
		var result = segmenter.split(text);
		assertEquals(expected, result.units().stream().map(Unit::text).toList());
		for (Unit unit : result.units()) {
			assertEquals(unit.text(), BodyTextChecks.slice(text, unit.start(), unit.end()));
		}
	}

	@Test
	void recordsUncertaintyWithoutInventingPunctuation() {
		var result = segmenter.split("교육을 실시했다 만족도 조사를 진행했다");
		assertTrue(result.uncertain());
		assertEquals("교육을 실시했다 만족도 조사를 진행했다", result.units().getFirst().text());
	}

	@Test
	void mergesOnlyInferredBoundariesAndPreservesOriginalWhitespaceAndOffsets() {
		String first = "😀 교육을 실시했다  \n만족도 조사를 진행했다.";
		String text = "  " + first + "  다음 교육을 준비했다.";
		var result = segmenter.split(text);
		assertTrue(result.uncertain());
		assertEquals(List.of(first, "다음 교육을 준비했다."), result.units().stream().map(Unit::text).toList());
		assertEquals(2, result.units().getFirst().start());
		assertEquals(2 + first.codePointCount(0, first.length()), result.units().getFirst().end());
		assertDoesNotThrow(() -> BodyTextChecks.validate(text, text, result.units()));
	}

	@Test
	void doesNotWarnForCompleteBoundariesItemsOrAbbreviatedYears() {
		for (String text : List.of("교육 완료. 조사 예정.", "주민 의견 수렴", "'22. 5월~12월", "참고 1. 추진 결과")) {
			assertFalse(segmenter.split(text).uncertain(), text);
		}
	}

	@Test
	void retainsWarningForUnclosedBrackets() {
		assertTrue(segmenter.split("교육을 실시했다(주민 대상").uncertain());
	}

	@Test
	void sharedNativeModelCanSplitConcurrentRequests() throws Exception {
		try (var executor = Executors.newFixedThreadPool(4)) {
			Callable<TextSegmenter.Result> split = () -> segmenter.split("교육을 실시했다 만족도 조사를 진행했다. 교육 완료.");
			for (var result : executor.invokeAll(java.util.Collections.nCopies(16, split))) {
				var actual = result.get();
				assertTrue(actual.uncertain());
				assertEquals(List.of("교육을 실시했다 만족도 조사를 진행했다.", "교육 완료."),
						actual.units().stream().map(Unit::text).toList());
			}
		}
	}

	@Test
	void preservesSpacesAndCountsSupplementaryCodePoints() {
		var units = segmenter.split("  교육 완료.  조사 예정.  ").units();
		assertEquals(List.of(2, 10), units.stream().map(Unit::start).toList());
		assertEquals(List.of(8, 16), units.stream().map(Unit::end).toList());
		assertEquals(3, segmenter.split("가😀나").units().getFirst().end());
		assertEquals("교육 완료.", segmenter.split("\u00a0교육 완료.\u00a0").units().getFirst().text());
	}

	@Test
	void repeatedTextReceivesDistinctIdsAndEmptyTextHasNoUnits() {
		var units = segmenter.split("교육 완료. 교육 완료.").units();
		assertNotEquals(units.getFirst().id(), units.getLast().id());
		assertEquals(List.of(), segmenter.split(" \n ").units());
	}

	@Test
	void rejectsNullAsInvalidContent() {
		var error = assertThrowsExactly(ReportException.class, () -> segmenter.split(null));
		assertSame(ReportErrorCode.INVALID_CONTENT, error.getBaseErrorCode());
	}

	@Test
	void rejectsNativeResultThatDropsOriginalText() {
		var sentence = new Kiwi.Sentence();
		sentence.start = 0;
		sentence.end = 1;
		sentence.text = "원";
		var broken = new TextSegmenter(returning(sentence));
		var error = assertThrowsExactly(ReportException.class, () -> broken.split("원문"));
		assertSame(ReportErrorCode.TEXT_SEGMENTATION_FAILED, error.getBaseErrorCode());
	}

	@Test
	void rejectsNativeOffsetsInsideAnEmoji() {
		var sentence = new Kiwi.Sentence();
		sentence.start = 0;
		sentence.end = 2;
		sentence.text = "가😀나".substring(0, 2);
		var broken = new TextSegmenter(returning(sentence));
		var error = assertThrowsExactly(ReportException.class, () -> broken.split("가😀나"));
		assertSame(ReportErrorCode.TEXT_SEGMENTATION_FAILED, error.getBaseErrorCode());
	}

	@Test
	void preservesNativeFailureCause() {
		var cause = new IllegalStateException("native failure");
		Kiwi failing = new Kiwi(0) {
			@Override
			public Sentence[] splitIntoSents(String text, int matchOption) { throw cause; }
		};
		var error = assertThrowsExactly(ReportException.class, () -> new TextSegmenter(failing).split("원문"));
		assertSame(ReportErrorCode.TEXT_SEGMENTATION_FAILED, error.getBaseErrorCode());
		assertSame(cause, error.getCause());
	}

	private static Kiwi returning(Kiwi.Sentence... sentences) {
		return new Kiwi(0) {
			@Override
			public Sentence[] splitIntoSents(String text, int matchOption) { return sentences; }
		};
	}
}
