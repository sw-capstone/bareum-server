package com.bareum.server.domain.report.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.bareum.server.domain.report.body.BodyAssembler;
import com.bareum.server.domain.report.body.BodyInput;
import com.bareum.server.domain.report.body.ReportBody;
import com.bareum.server.domain.report.body.TextSegmenter;
import com.bareum.server.domain.report.body.pdf.PdfBodyConverter;
import com.bareum.server.domain.report.enums.SourceFormat;

import kr.pe.bab2min.Kiwi;

class ReportBodyConfigTests {
	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(ReportBodyConfig.class)
			.withPropertyValues("report.segmentation.model-path=" + System.getProperty("kiwi.model.path"));

	@Test
	void reusesOneNativeModelAndClosesItWithTheApplicationContext() {
		var nativeModel = new AtomicReference<Kiwi>();
		context.run(application -> {
			assertNull(application.getStartupFailure());
			Kiwi kiwi = application.getBean(Kiwi.class);
			nativeModel.set(kiwi);
			assertTrue(kiwi.isAlive());
			assertEquals(1, application.getBeansOfType(Kiwi.class).size());
			assertSame(application.getBean(TextSegmenter.class), application.getBean(TextSegmenter.class));
			assertNotNull(application.getBean(PdfBodyConverter.class));
			String text = "교육을 실시했다 만족도 조사를 진행했다";
			var input = new BodyInput(SourceFormat.TEXT, List.of(), List.of(
					new BodyInput.TextInput("p", "para", null, null, text, text, List.of(), null, null)), List.of(),
					List.of());
			var body = application.getBean(BodyAssembler.class).assemble(input).getBody();
			assertEquals(text, ((ReportBody.TextBlock)body.blocks().getFirst()).units().getFirst().text());
			assertEquals("BOUNDARY_UNCERTAIN", body.warnings().getFirst().code());
		});
		assertFalse(nativeModel.get().isAlive());
	}

	@Test
	void missingModelPreventsStartupInsteadOfUsingAnotherSplitter() {
		context.withPropertyValues("report.segmentation.model-path=build/nonexistent-kiwi-model")
				.run(application -> assertNotNull(application.getStartupFailure()));
	}
}
