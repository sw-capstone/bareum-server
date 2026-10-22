package com.bareum.server.domain.report.body;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.bareum.server.domain.report.body.ReportBody.TableBlock;
import com.bareum.server.domain.report.body.ReportBody.TextBlock;
import com.bareum.server.domain.report.body.pdf.PdfBodyConverter;
import com.bareum.server.domain.report.enums.PdfParseErrorAction;
import com.bareum.server.domain.report.enums.PdfParseErrorCode;
import com.bareum.server.domain.report.enums.SourceFormat;
import com.bareum.server.domain.report.parsing.HttpPdfParser;
import com.bareum.server.domain.report.parsing.PdfParseRequest;
import com.bareum.server.domain.report.parsing.PdfParseResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 AI 파싱 API의 성공 결과를 본문으로 변환하고 실패 응답의 오류 정보가 유지되는지 검사한다. */
@EnabledIfEnvironmentVariable(named = "BAREUM_PDF_API_CASES", matches = ".+")
class PdfApiIntegrationTests {

	@RegisterExtension
	private static final KiwiFixture kiwi = new KiwiFixture();
	private final JsonMapper mapper = JsonMapper.builder().build();

	@TestFactory
	Stream<DynamicTest> realPdfApiResponsesConvertToValidBodiesAndPreserveSources() throws Exception {
		return cases().filter(sample -> sample.expectedError() == null)
				.map(sample -> DynamicTest.dynamicTest(sample.name(), () -> verifySuccess(sample)));
	}

	@TestFactory
	Stream<DynamicTest> realPdfApiFailuresPreserveErrorActionAndRequestIdentity() throws Exception {
		return cases().filter(sample -> sample.expectedError() != null)
				.map(sample -> DynamicTest.dynamicTest(sample.name(), () -> verifyFailure(sample)));
	}

	private Stream<Case> cases() throws Exception {
		var cases = mapper.readValue(Files.readString(Path.of(System.getenv("BAREUM_PDF_API_CASES"))), Case[].class);
		assertTrue(cases.length >= 3);
		return Arrays.stream(cases);
	}

	private PdfParseResponse parse(Case sample) {
		try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			var parser = new HttpPdfParser(http, URI.create(System.getenv("BAREUM_PDF_API_URL")),
					System.getenv("BAREUM_PDF_API_TOKEN"), Duration.ofSeconds(320));
			return parser.parse(sample.request());
		}
	}

	private void verifyFailure(Case sample) {
		var failed = assertInstanceOf(PdfParseResponse.Failed.class, parse(sample), sample.name());
		assertAll(
				() -> assertEquals(sample.expectedError(), failed.error().code(), sample.name()),
				() -> assertEquals(sample.expectedAction(), failed.error().action(), sample.name()),
				() -> assertEquals(sample.request().reportId(), failed.reportId(), sample.name()),
				() -> assertEquals(sample.request().parseAttemptId(), failed.parseAttemptId(), sample.name()));
		System.out.printf("PDF failure contract: %s code=%s action=%s reportId=%d parseAttemptId=%d%n",
				sample.name(), failed.error().code(), failed.error().action(), failed.reportId(),
				failed.parseAttemptId());
	}

	private void verifySuccess(Case sample) {
		var converter = new PdfBodyConverter(new BodyAssembler(new TextSegmenter(kiwi.getKiwi())));
		var succeeded = assertInstanceOf(PdfParseResponse.Succeeded.class, parse(sample), sample.name());
		JsonNode parsed = succeeded.parsed();
		var body = converter.convertParsed(parsed.toString()).getBody();
		assertEquals(SourceFormat.PDF, body.sourceFormat());
		assertEquals(parsed.get("pages").size(), body.pages().size());
		assertEquals(parsed.get("blocks").size(), body.blocks().size());
		assertFalse(body.blocks().isEmpty());
		assertDoesNotThrow(() -> new BodyJson().tree(body));
		assertSources(parsed, body);
		if (sample.requireMergedCell()) {
			assertTrue(body.blocks()
					.stream()
					.filter(TableBlock.class::isInstance)
					.map(TableBlock.class::cast)
					.flatMap(table -> table.cells().stream())
					.anyMatch(cell -> cell.rowspan() > 1 || cell.colspan() > 1));
		}
		for (JsonNode warning : parsed.get("warnings")) {
			assertTrue(body.warnings().stream().anyMatch(w -> w.code().equals(warning.get("code").asString())));
		}
		System.out.printf("PDF contract: %s quality=%s pages=%d blocks=%d warnings=%d%n",
				sample.name(), succeeded.extractionQuality(), body.pages().size(), body.blocks().size(),
				body.warnings().size());
	}

	private void assertSources(JsonNode parsed, ReportBody body) {
		Map<String, JsonNode> originals = new HashMap<>();
		parsed.get("blocks").forEach(block -> originals.put(block.get("id").asString(), block));
		for (ReportBody.Block block : body.blocks()) {
			JsonNode original = originals.get(block.sourceRefs().getFirst().parserBlockId());
			assertNotNull(original);
			assertEquals(original.get("page").intValue(), block.sourceRefs().getFirst().location().page());
			switch (block) {
				case TextBlock text -> {
					assertEquals(original.get("text").asString(), text.text());
					assertEquals(original.get("source_text").asString(), text.sourceText());
				}
				case TableBlock table -> {
					for (var cell : table.cells()) {
						var source = cell.sourceRefs().getFirst();
						assertEquals(original.get("rows").get(source.parserRow()).get(source.parserColumn()).asString(),
								cell.text());
						assertEquals(original.get("source_rows")
								.get(source.parserRow())
								.get(source.parserColumn())
								.asString(), cell.sourceText());
					}
				}
			}
		}
	}

	/** 실제 API에 보낼 요청과 해당 요청에서 기대하는 검증 결과를 담는다. */
	record Case(
		String name,
		PdfParseRequest request,
		PdfParseErrorCode expectedError,
		PdfParseErrorAction expectedAction,
		boolean requireMergedCell
	) {
	}
}
