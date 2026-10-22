package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.ReportBody.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.bareum.server.domain.report.body.pdf.PdfBodyConverter;
import com.bareum.server.domain.report.enums.SourceFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class PdfBodyConverterTests {
	@RegisterExtension
	private static final KiwiFixture kiwi = new KiwiFixture();
	private PdfBodyConverter converter;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@BeforeEach
	void setUp() {
		converter = new PdfBodyConverter(new BodyAssembler(new TextSegmenter(kiwi.getKiwi())));
	}

	@Test
	void convertsParserBlocksSectionsCellsSourcesAndWarnings() throws Exception {
		ValidatedReportBody validated = converter.convertParsed(BodyFixtures.parsed("1.2.0"));
		var body = validated.getBody();
		assertEquals(SourceFormat.PDF, body.sourceFormat());
		assertEquals("UNROTATED_PDF_POINTS", body.pages().getFirst().coordinateSystem());
		var paragraph = (TextBlock)body.blocks().get(1);
		assertEquals("교육을 실시했다. 만족도 조사를 진행했다.", paragraph.text());
		assertEquals("교육을 실시했다.\n만족도 조사를 진행했다.", paragraph.sourceText());
		assertEquals(List.of("교육을 실시했다.", "만족도 조사를 진행했다."),
				paragraph.units().stream().map(Unit::text).toList());
		assertEquals(2, paragraph.sourceRefs().size());
		assertEquals("p1", paragraph.sourceRefs().getFirst().parserBlockId());
		assertEquals(body.blocks().getFirst().id(), paragraph.parentId());
		assertEquals(body.sections().getFirst().id(), paragraph.sectionId());
		var table = (TableBlock)body.blocks().getLast();
		assertEquals(5, table.cells().size());
		assertEquals(2, table.cells().get(2).rowspan());
		assertEquals("", table.cells().getLast().text());
		assertTrue(table.cells().getLast().units().isEmpty());
		assertEquals(2, table.cells().get(3).units().size());
		assertEquals(1, table.cells().get(3).sourceRefs().getFirst().parserRow());
		assertEquals(1, table.cells().get(3).sourceRefs().getFirst().parserColumn());
		assertEquals("unresolved", table.headerRelations().status());
		assertTrue(table.extraction().requiresVision());
		assertEquals(table.cells().get(3).id(), body.warnings().getFirst().target().id());
		assertEquals("new_warning_code", body.warnings().getLast().code());
		assertDoesNotThrow(() -> new BodyJson().tree(body));
	}

	private ReportBody changed(Consumer<ObjectNode> mutate) throws Exception {
		ObjectNode parsed = (ObjectNode)mapper.readTree(BodyFixtures.parsed("1.2.0"));
		mutate.accept(parsed);
		return converter.convertParsed(parsed.toString()).getBody();
	}

	private static ObjectNode table(ObjectNode parsed) {
		return (ObjectNode)parsed.get("blocks").get(3);
	}

	private ReportException rejects(ReportErrorCode expected, Consumer<ObjectNode> mutate) {
		var error = assertThrowsExactly(ReportException.class, () -> changed(mutate));
		assertSame(expected, error.getBaseErrorCode());
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getBaseErrorCode().getHttpStatus());
		return error;
	}

	@Test
	void missingGeometrySlotRemainsUnresolvedInsteadOfBecomingEmptyCell() throws Exception {
		var body = changed(parsed -> ((ArrayNode)table(parsed).get("cells")).remove(4));
		var table = (TableBlock)body.blocks().getLast();
		assertEquals("partial", table.structureStatus());
		assertEquals(List.of(new Slot(2, 1)), table.unresolvedSlots());
		assertEquals(4, table.cells().size());
		assertEquals("TABLE_STRUCTURE_UNCERTAIN", body.warnings().getLast().code());
	}

	@Test
	void missingCellBboxUsesBlockPrecisionAndKeepsParserPosition() throws Exception {
		var body = changed(parsed -> ((ObjectNode)table(parsed).get("cells").get(3)).remove("bbox"));
		var source = ((TableBlock)body.blocks().getLast()).cells().get(3).sourceRefs().getFirst();
		assertEquals("block", source.location().precision());
		assertEquals(1, source.parserRow());
		assertEquals(1, source.parserColumn());
	}

	@Test
	void unsupportedOutputVersionIsAReportParsingError() {
		rejects(ReportErrorCode.UNSUPPORTED_PARSE_SCHEMA_VERSION, parsed -> parsed.put("schema_version", "9.0.0"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", "null", "[]"})
	void rejectsUnreadableResponseFormat(String json) {
		var error = assertThrowsExactly(ReportException.class, () -> converter.convertParsed(json));
		assertSame(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT, error.getBaseErrorCode());
	}

	@Test
	void rejectsMissingRequiredFieldsAndWrongTypesAsResponseFormatErrors() {
		rejects(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT, parsed -> parsed.remove("schema_version"));
		rejects(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT,
				parsed -> ((ObjectNode)parsed.get("document")).remove("page_count"));
		rejects(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT,
				parsed -> ((ObjectNode)parsed.get("document")).put("page_count", "1"));
	}

	@Test
	void rejectsInvalidDocumentMetadata() {
		rejects(ReportErrorCode.INVALID_PARSE_DOCUMENT_METADATA, parsed -> parsed.putNull("document"));
		rejects(ReportErrorCode.INVALID_PARSE_DOCUMENT_METADATA,
				parsed -> ((ObjectNode)parsed.get("document")).put("page_count", 2));
		rejects(ReportErrorCode.INVALID_PARSE_DOCUMENT_METADATA, parsed -> parsed.putNull("parser"));
		rejects(ReportErrorCode.INVALID_PARSE_DOCUMENT_METADATA, parsed -> parsed.putArray("parser"));
	}

	@Test
	void rejectsTablesWithoutRowsOrColumns() {
		rejects(ReportErrorCode.INVALID_PARSE_TABLE_SIZE, parsed -> table(parsed).putArray("rows"));
		rejects(ReportErrorCode.INVALID_PARSE_TABLE_SIZE, parsed -> table(parsed).putArray("rows").addArray());
	}

	@Test
	void rejectsMismatchedSourceRowAndColumnCounts() {
		rejects(ReportErrorCode.PARSE_TABLE_ROWS_MISMATCH,
				parsed -> ((ArrayNode)table(parsed).get("source_rows")).remove(2));
		rejects(ReportErrorCode.PARSE_TABLE_ROWS_MISMATCH,
				parsed -> ((ArrayNode)table(parsed).get("source_rows").get(0)).remove(1));
	}

	@ParameterizedTest
	@CsvSource({"row, -1", "col, -1", "rowspan, 0", "colspan, 0",
		"row, 3", "col, 2", "rowspan, 4", "colspan, 3"})
	void rejectsInvalidCellPositionsAndSpans(String field, int value) {
		rejects(ReportErrorCode.INVALID_PARSE_CELL_RANGE,
				parsed -> ((ObjectNode)table(parsed).get("cells").get(0)).put(field, value));
	}

	@Test
	void rejectsOverlappingCellsWithoutReplacingTheErrorCode() {
		rejects(ReportErrorCode.PARSE_CELL_OVERLAP,
				parsed -> ((ObjectNode)table(parsed).get("cells").get(0)).put("rowspan", 2));
	}

	@Test
	void rejectsTextWithoutMatchingCellGeometry() {
		rejects(ReportErrorCode.UNMAPPED_PARSE_TABLE_TEXT, parsed -> ((ArrayNode)table(parsed).get("cells")).remove(3));
	}

	@Test
	void rejectsMissingSectionHeadingsAndAncestors() {
		rejects(ReportErrorCode.INVALID_PARSE_SECTION_REFERENCE,
				parsed -> ((ObjectNode)parsed.get("sections").get(0)).put("head_block_id", "missing"));
		rejects(ReportErrorCode.INVALID_PARSE_SECTION_REFERENCE,
				parsed -> ((ObjectNode)parsed.get("blocks").get(0)).put("parent_id", "missing"));
	}

	@Test
	void rejectsCyclicSectionAncestorChains() {
		rejects(ReportErrorCode.CYCLIC_PARSE_SECTION_REFERENCE, parsed -> {
			((ObjectNode)parsed.get("blocks").get(0)).put("parent_id", "p1");
			((ObjectNode)parsed.get("blocks").get(1)).put("parent_id", "note");
			((ObjectNode)parsed.get("blocks").get(2)).put("parent_id", "p1");
		});
	}

	@Test
	void distinguishesCommonBodyValidationFailuresFromParserFormatErrors() {
		rejects(ReportErrorCode.INVALID_PARSED_CONTENT,
				parsed -> ((ObjectNode)parsed.get("blocks").get(1)).put("parent_id", "missing"));
		rejects(ReportErrorCode.INVALID_PARSED_CONTENT,
				parsed -> ((ObjectNode)parsed.get("pages").get(0)).put("width", 0));
	}

	@Test
	void specificParsingErrorCodeReachesTheExistingExceptionHandler() {
		var error = rejects(ReportErrorCode.PARSE_CELL_OVERLAP,
				parsed -> ((ObjectNode)table(parsed).get("cells").get(0)).put("rowspan", 2));
		var response = new GlobalExceptionHandler()
				.handleBusinessException(error, new MockHttpServletRequest("POST", "/reports"));
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
		assertEquals("REPORT-0021", response.getBody().getProperties().get("code"));
		assertEquals(ReportErrorCode.PARSE_CELL_OVERLAP.getMessage(), response.getBody().getDetail());
	}

	@Test
	void reparsingCreatesNewServiceIds() throws Exception {
		String source = BodyFixtures.parsed("1.2.0");
		var first = converter.convertParsed(source).getBody();
		var second = converter.convertParsed(source).getBody();
		assertNotEquals(first.blocks().getFirst().id(), second.blocks().getFirst().id());
		assertEquals(first.blocks().getFirst().sourceRefs(), second.blocks().getFirst().sourceRefs());
	}

	@Test
	void inferredSentenceBoundaryKeepsPdfTextAndAddsTargetedWarning() throws Exception {
		String text = "교육을 실시했다  만족도 조사를 진행했다";
		var body = changed(parsed -> ((ObjectNode)parsed.get("blocks").get(1)).put("text", text));
		var block = (TextBlock)body.blocks().get(1);
		assertEquals(text, block.text());
		assertEquals(List.of(text), block.units().stream().map(Unit::text).toList());
		var warning = body.warnings().getLast();
		assertEquals("BOUNDARY_UNCERTAIN", warning.code());
		assertEquals(new Target("block", block.id(), null), warning.target());
		assertDoesNotThrow(() -> new BodyJson().tree(body));
	}
}
