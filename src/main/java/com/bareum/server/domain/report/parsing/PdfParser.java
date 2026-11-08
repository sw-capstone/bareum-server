package com.bareum.server.domain.report.parsing;

import java.time.Duration;

/** 접수의 남은 처리 기한 안에서 PDF 파싱 요청을 보내고 결과를 받는다. */
public interface PdfParser {

	PdfParseResponse parse(PdfParseRequest request, Duration remaining);
}
