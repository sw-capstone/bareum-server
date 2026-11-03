package com.bareum.server.domain.report.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bareum.server.domain.report.body.BodyAssembler;
import com.bareum.server.domain.report.body.TextSegmenter;
import com.bareum.server.domain.report.body.pdf.PdfBodyConverter;

import kr.pe.bab2min.Kiwi;

/**
 * Kiwi 모델을 한 번 로드하고, 본문 처리에 필요한 객체 연결.
 * 서버 종료 시 자원 정리하는 설정 클래스
 */
@Configuration(proxyBeanMethods = false)
public class ReportBodyConfig {
	// 설정에서 모델 경로 읽고 Kiwi 생성
	@Bean(destroyMethod = "close")
	public Kiwi reportKiwi(@Value("${report.segmentation.model-path}") String modelPath) throws Exception {
		return Kiwi.init(modelPath, 1); // Kiwi 내부 작업 스레드 수: 1 (KiwiJava의 기본값 유지)
	}

	@Bean
	public TextSegmenter textSegmenter(Kiwi reportKiwi) {
		return new TextSegmenter(reportKiwi);
	}

	@Bean
	public BodyAssembler bodyAssembler(TextSegmenter textSegmenter) {
		return new BodyAssembler(textSegmenter);
	}

	@Bean
	public PdfBodyConverter pdfBodyConverter(BodyAssembler bodyAssembler) {
		return new PdfBodyConverter(bodyAssembler);
	}
}
