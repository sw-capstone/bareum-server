package com.bareum.server.domain.report.body.pdf;

import static com.bareum.server.domain.report.body.BodyInput.*;
import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * PDF 파서가 나눠서 보내 준 표의 텍스트와 셀 구조를 합쳐 TableInput을 만드는 클래스
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class PdfTableInput {
	//
	static TableInput convert(PdfExtraction.BlockData block, List<SourceRef> refs) {
		int rows = block.rows().size();
		int columns = block.rows().stream().mapToInt(List::size).max().orElse(0);
		if (rows <= 0 || columns <= 0) {
			throw new ReportException(ReportErrorCode.INVALID_PARSE_TABLE_SIZE);
		}
		if (block.sourceRows().size() != rows) {
			throw new ReportException(ReportErrorCode.PARSE_TABLE_ROWS_MISMATCH);
		}
		for (int row = 0; row < rows; row++) {
			if (block.sourceRows().get(row).size() != block.rows().get(row).size()) {
				throw new ReportException(ReportErrorCode.PARSE_TABLE_ROWS_MISMATCH);
			}
		}
		boolean[][] covered = new boolean[rows][columns];
		List<CellInput> cells = new ArrayList<>();
		for (PdfExtraction.CellData cell : block.cells()) {
			if (cell.row() < 0 || cell.col() < 0 || cell.rowspan() <= 0 || cell.colspan() <= 0
					|| (long)cell.row() + cell.rowspan() > rows || (long)cell.col() + cell.colspan() > columns
					|| cell.col() >= block.rows().get(cell.row()).size()) {
				throw new ReportException(ReportErrorCode.INVALID_PARSE_CELL_RANGE);
			}
			for (int r = cell.row(); r < cell.row() + cell.rowspan(); r++) {
				for (int c = cell.col(); c < cell.col() + cell.colspan(); c++) {
					if (covered[r][c]) {
						throw new ReportException(ReportErrorCode.PARSE_CELL_OVERLAP);
					}
					covered[r][c] = true;
				}
			}
			boolean located = cell.bbox() != null;
			SourceRef source = new SourceRef(block.id(), cell.row(), cell.col(),
					new PdfLocation("pdf", block.page(), located ? cell.bbox() : block.bbox(),
							located ? "cell" : "block"));
			cells.add(new CellInput(cellKey(block.id(), cell.row(), cell.col()), cell.row(), cell.col(),
					cell.rowspan(), cell.colspan(), block.rows().get(cell.row()).get(cell.col()),
					block.sourceRows().get(cell.row()).get(cell.col()), List.of(source),
					new Extraction(block.excludedFromRetrieval(), block.requiresVision())));
		}
		cells.sort(Comparator.comparingInt(CellInput::row).thenComparingInt(CellInput::column));
		List<Slot> unresolved = new ArrayList<>();
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < columns; c++) {
				if (!covered[r][c]) {
					if (c < block.rows().get(r).size() && !block.rows().get(r).get(c).isBlank()) {
						throw new ReportException(ReportErrorCode.UNMAPPED_PARSE_TABLE_TEXT);
					}
					unresolved.add(new Slot(r, c));
				}
			}
		}
		return new TableInput(block.id(), "table", block.parentId(), block.sectionId(), refs,
				new Extraction(block.excludedFromRetrieval(), block.requiresVision()), rows, columns,
				unresolved.isEmpty() ? "complete" : "partial", cells, unresolved, "unresolved", List.of(), List.of(),
				block.lineCount());
	}

	// 셀을 구분할 임시 키 생성
	static String cellKey(String block, int row, int column) { return block + "/cell/" + row + ":" + column; }
}
