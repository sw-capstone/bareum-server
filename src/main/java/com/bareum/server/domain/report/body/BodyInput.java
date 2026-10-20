package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.List;

import com.bareum.server.domain.report.enums.SourceFormat;

/**
 * 공통 본문을 만들 때 사용하는 입력 데이터.
 * 문자열 키로 블록과 셀 등을 연결하며, BodyAssembler에서 UUID와 분리된 텍스트 단위를 부여한다.
 */
public record BodyInput(
	SourceFormat sourceFormat,
	List<Page> pages,
	List<BlockInput> blocks,
	List<SectionInput> sections,
	List<WarningInput> warnings
) {
	public BodyInput {
		pages = List.copyOf(pages);
		blocks = List.copyOf(blocks);
		sections = List.copyOf(sections);
		warnings = List.copyOf(warnings);
	}

	public sealed interface BlockInput permits TextInput, TableInput {
		String key();
		String kind();
		String parentKey();
		String sectionKey();
		List<SourceRef> sourceRefs();
		Extraction extraction();
	}

	public record TextInput(
		String key,
		String kind,
		String parentKey,
		String sectionKey,
		String text,
		String sourceText,
		List<SourceRef> sourceRefs,
		Extraction extraction
	) implements BlockInput {
		public TextInput {
			sourceRefs = List.copyOf(sourceRefs);
		}
	}

	public record TableInput(
		String key,
		String kind,
		String parentKey,
		String sectionKey,
		List<SourceRef> sourceRefs,
		Extraction extraction,
		int rowCount,
		int columnCount,
		String structureStatus,
		List<CellInput> cells,
		List<Slot> unresolvedSlots,
		String headerStatus,
		List<HeaderInput> headers,
		List<NoteInput> unitNotes
	) implements BlockInput {
		public TableInput {
			sourceRefs = List.copyOf(sourceRefs);
			cells = List.copyOf(cells);
			unresolvedSlots = List.copyOf(unresolvedSlots);
			headers = List.copyOf(headers);
			unitNotes = List.copyOf(unitNotes);
		}
	}

	public record CellInput(
		String key,
		int row,
		int column,
		int rowspan,
		int colspan,
		String text,
		String sourceText,
		List<SourceRef> sourceRefs,
		Extraction extraction
	) {
		public CellInput {
			sourceRefs = List.copyOf(sourceRefs);
		}
	}

	public record SectionInput(
		String key,
		String headingKey,
		String parentKey
	) {
	}
	public record HeaderInput(
		String cellKey,
		List<String> headerKeys
	) {
		public HeaderInput {
			headerKeys = List.copyOf(headerKeys);
		}
	}
	public record NoteInput(
		String type,
		String key,
		int start,
		int end
	) {
	}
	public record TargetInput(
		String type,
		String key,
		Integer page
	) {
	}
	public record WarningInput(
		String stage,
		String code,
		TargetInput target,
		String message
	) {
	}
}
