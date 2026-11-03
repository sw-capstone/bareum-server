package com.bareum.server.domain.report.parsing;

/** PDF 파싱 요청을 보내고 결과를 받는 기능의 공통 인터페이스. */
public interface PdfParser {

	PdfParseResponse parse(PdfParseRequest request);
}
