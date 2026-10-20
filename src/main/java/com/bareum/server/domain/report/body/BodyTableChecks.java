package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 표의 셀 배치와 제목 셀 연결이 올바른지 검사하는 보조 클래스
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BodyTableChecks {
	private record Region(
		int row,
		int column,
		int rowspan,
		int colspan
	) {
		// 영역이 끝나는 경계 계산
		long bottom() { return (long)row + rowspan; }
		long right() { return (long)column + colspan; }
		// 두 영역이 표에서 같은 칸을 차지하는지 확인. 병합 셀이 다른 셀의 영역을 침범하는지 검사할 때 활용한다.
		boolean overlaps(Region other) {
			return row < other.bottom() && other.row < bottom()
					&& column < other.right() && other.column < right();
		}
	}
	// 표 전체 검사
	static void validate(TableBlock table) {
		if (table.rowCount() <= 0 || table.columnCount() <= 0 || table.structureStatus() == null
				|| !Set.of("complete", "partial", "unresolved").contains(table.structureStatus())) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		List<Region> regions = new ArrayList<>();
		Set<UUID> cellIds = new HashSet<>();
		long area = 0;
		int previousRow = -1;
		int previousColumn = -1;
		for (Cell cell : table.cells()) {
			if (!(cell.row() > previousRow || (cell.row() == previousRow && cell.column() > previousColumn))) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			Region region = new Region(cell.row(), cell.column(), cell.rowspan(), cell.colspan());
			add(region, regions, table);
			area += (long)cell.rowspan() * cell.colspan();
			cellIds.add(cell.id());
			previousRow = cell.row();
			previousColumn = cell.column();
		}
		for (Slot slot : table.unresolvedSlots()) {
			add(new Region(slot.row(), slot.column(), 1, 1), regions, table);
			area++;
		}
		if (area != (long)table.rowCount() * table.columnCount()
				|| ("complete".equals(table.structureStatus()) && !table.unresolvedSlots().isEmpty())) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		HeaderRelations headers = table.headerRelations();
		if (headers == null || headers.status() == null
				|| !Set.of("resolved", "unresolved").contains(headers.status())) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		for (HeaderRelation relation : headers.items()) {
			if (!cellIds.contains(relation.cellId()) || relation.headerCellIds().isEmpty()
					|| new HashSet<>(relation.headerCellIds()).size() != relation.headerCellIds().size()
					|| !cellIds.containsAll(relation.headerCellIds())) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
		}
	}

	private static void add(Region region, List<Region> regions, TableBlock table) {
		if (region.row < 0 || region.column < 0 || region.rowspan <= 0 || region.colspan <= 0
				|| region.bottom() > table.rowCount() || region.right() > table.columnCount()
				|| regions.stream().anyMatch(region::overlaps)) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		regions.add(region);
	}
}
