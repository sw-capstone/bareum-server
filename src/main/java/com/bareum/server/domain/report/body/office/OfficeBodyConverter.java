package com.bareum.server.domain.report.body.office;

import static com.bareum.server.domain.report.body.BodyInput.*;
import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.bareum.server.domain.report.body.BodyAssembler;
import com.bareum.server.domain.report.body.BodyInput;
import com.bareum.server.domain.report.body.ValidatedReportBody;
import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.enums.SourceFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.parsing.office.OfficeReportParser;

import lombok.RequiredArgsConstructor;

/** Office 문서 구조를 서비스 공통 본문으로 변환한다. */
@Component
@RequiredArgsConstructor
public class OfficeBodyConverter {
	private static final Pattern UNIT_NOTE = Pattern.compile("^[\\s(（\\[【]*단위\\s*[:：]\\s*\\S");
	private final BodyAssembler assembler;

	public Result convert(OfficeReportParser.ParsedReport report) {
		List<BlockInput> blocks = new ArrayList<>();
		List<WarningInput> warnings = new ArrayList<>();
		for (var omission : report.omissions()) {
			warnings.add(warning("OFFICE_TEXT_OMITTED", "document", null,
					"지원하지 않는 요소 " + omission.element() + "의 텍스트를 생략했습니다. 위치: " + position(omission.source())));
		}
		List<OfficeReportParser.Block> extracted = report.blocks();
		for (int index = 0; index < extracted.size(); index++) {
			String key = blockKey(index);
			switch (extracted.get(index)) {
				case OfficeReportParser.Paragraph paragraph -> blocks.add(new TextInput(key, "para", null, null,
						paragraph.text(), paragraph.text(), List.of(paragraphRef(paragraph, null, null)), null, null));
				case OfficeReportParser.Table table ->
					blocks.add(table(table, key, adjacentNotes(extracted, index), warnings));
			}
		}
		boolean hasText = blocks.stream().anyMatch(block -> switch (block) {
			case TextInput text -> !text.text().isBlank();
			case TableInput table -> table.cells().stream().anyMatch(cell -> !cell.text().isBlank());
		});
		if (!hasText) {
			throw new ReportException(ReportErrorCode.NO_EXTRACTABLE_TEXT);
		}
		ExtractionQuality quality = warnings.isEmpty() ? ExtractionQuality.COMPLETE : ExtractionQuality.PARTIAL;
		warnings.add(new WarningInput("composition", "LINE_COUNT_UNAVAILABLE", new TargetInput("document", null, null),
				"렌더링 줄 수를 확인할 수 없어 줄 수가 필요한 검사를 수행할 수 없습니다."));
		var body = assembler.assemble(new BodyInput(SourceFormat.valueOf(report.format().name()), List.of(),
				blocks, List.of(), warnings));
		return new Result(body, quality);
	}

	private static TableInput table(
		OfficeReportParser.Table table,
		String key,
		List<NoteInput> adjacentNotes,
		List<WarningInput> warnings
	) {
		List<CellInput> cells = new ArrayList<>();
		List<NoteInput> notes = new ArrayList<>(adjacentNotes);
		BitSet occupied = new BitSet(table.rows() * table.columns());
		for (var cell : table.cells()) {
			String cellKey = key + "/" + cell.row() + "/" + cell.column();
			List<SourceRef> refs = new ArrayList<>();
			for (var item : cell.content()) {
				switch (item) {
					case OfficeReportParser.Paragraph paragraph ->
						refs.add(paragraphRef(paragraph, cell.row(), cell.column()));
					case OfficeReportParser.Table nested ->
						warnings.add(warning("OFFICE_NESTED_TABLE_OMITTED", "cell", cellKey,
								"셀에 포함된 중첩 표를 생략했습니다. 위치: " + position(nested.source())));
				}
			}
			if (refs.isEmpty()) {
				refs.add(ref(cell.source(), cell.row(), cell.column(), 0, 0));
			}
			String text = cell.text();
			cells.add(new CellInput(cellKey, cell.row(), cell.column(), cell.rowSpan(), cell.columnSpan(),
					text, text, refs, null));
			if (UNIT_NOTE.matcher(text).find()) {
				notes.add(new NoteInput("cell", cellKey, 0, length(text)));
			}
			for (int row = cell.row(); row < cell.row() + cell.rowSpan(); row++) {
				int start = row * table.columns() + cell.column();
				occupied.set(start, start + cell.columnSpan());
			}
		}
		List<Slot> holes = new ArrayList<>();
		for (int slot = occupied.nextClearBit(0); slot < table.rows() * table.columns(); slot =
				occupied.nextClearBit(slot + 1)) {
			holes.add(new Slot(slot / table.columns(), slot % table.columns()));
		}
		if (!holes.isEmpty()) {
			warnings.add(warning("OFFICE_TABLE_SLOTS_UNRESOLVED", "block", key,
					"원본에서 셀 정보를 확인할 수 없는 표 위치가 있습니다."));
		}
		return new TableInput(key, "table", null, null, List.of(ref(table.source(), null, null, null, null)),
				null, table.rows(), table.columns(), holes.isEmpty() ? "complete" : "partial", cells, holes,
				"unresolved", List.of(), notes, null);
	}

	private static List<NoteInput> adjacentNotes(List<OfficeReportParser.Block> blocks, int tableIndex) {
		List<NoteInput> notes = new ArrayList<>();
		for (int index : new int[] {
			tableIndex - 1, tableIndex + 1
		}) {
			if (index >= 0 && index < blocks.size()
					&& blocks.get(index) instanceof OfficeReportParser.Paragraph paragraph
					&& UNIT_NOTE.matcher(paragraph.text()).find()) {
				notes.add(new NoteInput("block", blockKey(index), 0, length(paragraph.text())));
			}
		}
		return notes;
	}

	private static SourceRef paragraphRef(OfficeReportParser.Paragraph paragraph, Integer row, Integer column) {
		return ref(paragraph.source(), row, column, paragraph.start(), paragraph.start() + length(paragraph.text()));
	}

	private static SourceRef
			ref(OfficeReportParser.Source source, Integer row, Integer column, Integer start, Integer end) {
		return new SourceRef(position(source), row, column, null,
				new OfficeLocation(source.part(), source.path(), start, end));
	}

	private static String position(OfficeReportParser.Source source) {
		return source.part() + "#" + source.path();
	}

	private static int length(String text) {
		return text.codePointCount(0, text.length());
	}

	private static String blockKey(int index) {
		return "block-" + index;
	}

	private static WarningInput warning(String code, String target, String key, String message) {
		return new WarningInput("extraction", code, new TargetInput(target, key, null), message);
	}

	public record Result(
		ValidatedReportBody body,
		ExtractionQuality quality
	) {
	}
}
