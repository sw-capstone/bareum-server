package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import kr.pe.bab2min.Kiwi;
import lombok.RequiredArgsConstructor;

/**
 * 문단이나 표 셀의 텍스트를 작은 단위로 나누고, 각 단위에 ID와 원문 위치를 붙이는 클래스
 */
@RequiredArgsConstructor
public final class TextSegmenter {
	private final Kiwi kiwi;
	private final Supplier<UUID> ids;

	public TextSegmenter(Kiwi kiwi) { this(kiwi, UUID::randomUUID); }

	// 결과를 묶는 내부 레코드
	public record Result(
		List<Unit> units,
		boolean uncertain
	) {
		public Result {
			units = List.copyOf(units);
		}
	}

	// 전체 처리 실행. 외부에서 이 메서드 호출
	public Result split(String text) {
		if (text == null) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		if (blank(text, 0, text.length())) {
			return new Result(List.of(), false);
		}
		Kiwi.Sentence[] sentences;
		try {
			sentences = kiwi.splitIntoSents(text, Kiwi.Match.allWithNormalizing);
		} catch (RuntimeException exception) {
			throw new ReportException(ReportErrorCode.TEXT_SEGMENTATION_FAILED, exception);
		}
		validateSpans(text, sentences);
		List<Unit> units = new ArrayList<>();
		boolean uncertain = hasUnclosedDelimiter(text);
		int start = sentences[0].start;
		for (int i = 0; i < sentences.length - 1; i++) {
			// 종결 문장부호 없이 추정한 경계는 다시 합친다.
			if (hasEndingPunctuation(text, sentences[i].end)) {
				add(text, start, sentences[i].end, units);
				start = sentences[i + 1].start;
			} else {
				uncertain = true;
			}
		}
		add(text, start, sentences[sentences.length - 1].end, units);
		return new Result(units, uncertain);
	}

	// Kiwi 결과 검증
	private static void validateSpans(String text, Kiwi.Sentence[] sentences) {
		if (sentences == null || sentences.length == 0) {
			throw new ReportException(ReportErrorCode.TEXT_SEGMENTATION_FAILED);
		}
		int previous = 0;
		for (Kiwi.Sentence sentence : sentences) {
			if (sentence == null || sentence.start < previous || sentence.start >= sentence.end
					|| sentence.end > text.length() || !codePointBoundary(text, sentence.start)
					|| !codePointBoundary(text, sentence.end)
					|| !text.substring(sentence.start, sentence.end).equals(sentence.text)
					|| !blank(text, previous, sentence.start)) {
				throw new ReportException(ReportErrorCode.TEXT_SEGMENTATION_FAILED);
			}
			previous = sentence.end;
		}
		if (!blank(text, previous, text.length())) {
			throw new ReportException(ReportErrorCode.TEXT_SEGMENTATION_FAILED);
		}
	}

	// 검사 위치가 문자의 중간인지 확인. Java에서는 일부 이모지를 두 개의 char로 표현하므로, 그 사이를 자르지 않도록 한다.
	private static boolean codePointBoundary(String text, int position) {
		return position == 0 || position == text.length()
				|| !Character.isHighSurrogate(text.charAt(position - 1))
				|| !Character.isLowSurrogate(text.charAt(position));
	}

	// 구간이 비어 있거나 공백뿐인지 확인. 띄어쓰기뿐 아니라 탭, 줄바꿈 등도 확인.
	private static boolean blank(String text, int start, int end) {
		return text.substring(start, end).codePoints().allMatch(BodyTextChecks::space);
	}

	// 종결 문장부호 확인
	private static boolean hasEndingPunctuation(String text, int end) {
		while (end > 0) {
			int cp = text.codePointBefore(end);
			if (BodyTextChecks.space(cp) || ")]}”’\"'".indexOf(cp) >= 0) {
				end -= Character.charCount(cp);
			} else {
				return ".!?。？！".indexOf(cp) >= 0;
			}
		}
		return false;
	}

	// 닫히지 않은 기호 확인
	private static boolean hasUnclosedDelimiter(String text) {
		Deque<Character> closing = new ArrayDeque<>();
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (!closing.isEmpty() && closing.peek() == ch) {
				closing.pop();
				continue;
			}
			Character close = switch (ch) {
				case '\'' -> (i > 0 && Character.isLetterOrDigit(text.charAt(i - 1)))
						|| (i + 1 < text.length() && Character.isDigit(text.charAt(i + 1)))
								? null : '\'';
				case '(' -> ')';
				case '[' -> ']';
				case '{' -> '}';
				case '“' -> '”';
				case '‘' -> '’';
				case '"' -> '"';
				default -> null;
			};
			if (close != null) {
				closing.push(close);
			}
		}
		return !closing.isEmpty();
	}

	// 최종 Unit 생성.
	private void add(String owner, int start, int end, List<Unit> units) {
		while (start < end && BodyTextChecks.space(owner.codePointAt(start))) {
			start += Character.charCount(owner.codePointAt(start));
		}
		while (end > start && BodyTextChecks.space(owner.codePointBefore(end))) {
			end -= Character.charCount(owner.codePointBefore(end));
		}
		if (start == end) {
			return;
		}
		String value = owner.substring(start, end);
		units.add(new Unit(ids.get(),
				owner.codePointCount(0, start), owner.codePointCount(0, end), value));
	}
}
