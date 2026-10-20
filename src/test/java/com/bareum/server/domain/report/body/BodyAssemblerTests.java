package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.BodyInput.*;
import static com.bareum.server.domain.report.body.ReportBody.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.bareum.server.domain.report.enums.SourceFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

class BodyAssemblerTests {
	@RegisterExtension
	private static final KiwiFixture kiwi = new KiwiFixture();
	private BodyAssembler assembler;

	@BeforeEach
	void setUp() {
		assembler = new BodyAssembler(new TextSegmenter(kiwi.getKiwi()));
	}

	@Test
	void structuredInputAssemblesParagraphsAndCellsUsingOnePipeline() {
		var heading = new TextInput("h", "heading", null, "s", "추진 계획", "추진 계획", List.of(), null);
		var text = new TextInput("p", "para", "h", "s", "교육 완료.  조사 예정.", "교육 완료.  조사 예정.", List.of(), null);
		var cells = List.of(
				new CellInput("date", 0, 0, 1, 1, "2026. 10. 17.", "2026. 10. 17.", List.of(), null),
				new CellInput("amount", 0, 1, 1, 1, "1,000,000원", "1,000,000원", List.of(), null));
		var table = new TableInput("t", "table", "h", "s", List.of(), null, 1, 2,
				"complete", cells, List.of(), "resolved", List.of(), List.of());
		var input = new BodyInput(SourceFormat.TEXT, List.of(), List.of(heading, text, table),
				List.of(new SectionInput("s", "h", null)), List.of());
		var body = assembler.assemble(input);
		var paragraph = (TextBlock)body.blocks().get(1);
		assertEquals(List.of("교육 완료.", "조사 예정."), paragraph.units().stream().map(Unit::text).toList());
		assertEquals(List.of(0, 8), paragraph.units().stream().map(Unit::start).toList());
		assertEquals(body.blocks().getFirst().id(), paragraph.parentId());
		assertEquals(body.sections().getFirst().id(), paragraph.sectionId());
		var assembledTable = (TableBlock)body.blocks().get(2);
		assertEquals(List.of("2026. 10. 17.", "1,000,000원"),
				assembledTable.cells().stream().map(cell -> cell.units().getFirst().text()).toList());
		assertTrue(body.pages().isEmpty());
		assertDoesNotThrow(() -> new BodyJson().tree(body));
	}

	@Test
	void reassemblingInputGeneratesNewIdsAndPreservesUncertainText() {
		String text = "교육을 실시했다 만족도 조사를 진행했다";
		var input = new BodyInput(SourceFormat.TEXT, List.of(),
				List.of(new TextInput("a", "para", null, null, text, text, List.of(), null)), List.of(), List.of());
		var first = assembler.assemble(input);
		var second = assembler.assemble(input);
		var block = (TextBlock)first.blocks().getFirst();
		assertEquals(text, block.text());
		assertEquals(text, block.units().getFirst().text());
		assertEquals("BOUNDARY_UNCERTAIN", first.warnings().getFirst().code());
		assertEquals(block.id(), first.warnings().getFirst().target().id());
		assertNotEquals(block.id(), second.blocks().getFirst().id());
	}

	@Test
	void bodyCollectionsAreImmutable() {
		var body = assembler.assemble(new BodyInput(SourceFormat.TEXT, List.of(), List.of(), List.of(), List.of()));
		assertThrows(UnsupportedOperationException.class, () -> body.blocks().clear());
	}

	@Test
	void uncertainParagraphsAndCellsKeepTheirTextAndHaveSeparateWarningTargets() {
		String text = "교육을 실시했다  만족도 조사를 진행했다";
		var paragraph = new TextInput("p", "para", null, null, text, text, List.of(), null);
		var cell = new CellInput("c", 0, 0, 1, 1, text, text, List.of(), null);
		var table = new TableInput("t", "table", null, null, List.of(), null, 1, 1,
				"complete", List.of(cell), List.of(), "resolved", List.of(), List.of());
		var body = assembler.assemble(new BodyInput(SourceFormat.TEXT, List.of(),
				List.of(paragraph, table), List.of(), List.of()));
		var block = (TextBlock)body.blocks().getFirst();
		var assembledCell = ((TableBlock)body.blocks().getLast()).cells().getFirst();
		assertEquals(List.of(text), block.units().stream().map(Unit::text).toList());
		assertEquals(List.of(text), assembledCell.units().stream().map(Unit::text).toList());
		assertEquals(2, body.warnings().size());
		assertEquals(new Target("block", block.id(), null), body.warnings().getFirst().target());
		assertEquals(new Target("cell", assembledCell.id(), null), body.warnings().getLast().target());
		for (Warning warning : body.warnings()) {
			assertEquals("segmentation", warning.stage());
			assertEquals("BOUNDARY_UNCERTAIN", warning.code());
		}
	}

	@Test
	void rejectsInvalidBodyBeforeReturningValidatedResult() {
		var input = new BodyInput(SourceFormat.PDF, List.of(), List.of(), List.of(), List.of());
		var error = assertThrows(ReportException.class, () -> assembler.assemble(input));
		assertEquals(ReportErrorCode.INVALID_CONTENT, error.getBaseErrorCode());
	}
}
