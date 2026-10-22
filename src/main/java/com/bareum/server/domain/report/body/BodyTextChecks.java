package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.List;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 문단이나 셀의 텍스트와 그 안의 Unit들이 서로 맞는지 검사하는 보조 클래스
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BodyTextChecks {
	// 텍스트와 단위 목록 전체 검사
	static void validate(String text, String sourceText, List<Unit> units) {
		if (text == null || sourceText == null) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		int previousEnd = 0;
		for (Unit unit : units) {
			range(text, unit.start(), unit.end());
			if (unit.start() < previousEnd) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			String actual = slice(text, unit.start(), unit.end());
			if (!actual.equals(unit.text()) || blank(actual) || space(actual.codePointAt(0))
					|| space(actual.codePointBefore(actual.length()))
					|| !blank(slice(text, previousEnd, unit.start()))) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			previousEnd = unit.end();
		}
		if (!blank(slice(text, previousEnd, text.codePointCount(0, text.length())))) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
	}

	// 단위의 범위가 유효한지 확인
	static void range(String text, int start, int end) {
		if (start < 0 || start >= end || end > text.codePointCount(0, text.length())) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
	}

	static String slice(String text, int start, int end) {
		return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
	}

	// 문자 하나가 공백인지 확인
	static boolean space(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
	}

	// 문자열 전체가 공백인지 확인
	private static boolean blank(String value) { return value.codePoints().allMatch(BodyTextChecks::space); }
}
