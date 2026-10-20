package com.bareum.server.domain.report.body;

import java.util.List;
import java.util.UUID;

import com.bareum.server.domain.report.enums.SourceFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 보고서 공통 본문 전체의 데이터 구조
 */
public record ReportBody(
	@JsonProperty(required = true) String schemaVersion,
	@JsonProperty(required = true) String offsetUnit,
	@JsonProperty(required = true) SourceFormat sourceFormat,
	@JsonProperty(required = true) List<Page> pages,
	@JsonProperty(required = true) List<Block> blocks,
	@JsonProperty(required = true) List<Section> sections,
	@JsonProperty(required = true) List<Warning> warnings
) {
	public ReportBody {
		// 전달받은 목록을 수정 불가능한 목록으로 복사한다
		pages = List.copyOf(pages);
		blocks = List.copyOf(blocks);
		sections = List.copyOf(sections);
		warnings = List.copyOf(warnings);
	}

	// 원본 PDF 페이지 정보
	public record Page(
		@JsonProperty(required = true) int number,
		@JsonProperty(required = true) double width,
		@JsonProperty(required = true) double height,
		@JsonProperty(required = true) String coordinateSystem
	) {
	}

	// 본문 블록의 공통 타입
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({
		@JsonSubTypes.Type(TextBlock.class), @JsonSubTypes.Type(TableBlock.class)
	})
	public sealed interface Block permits TextBlock, TableBlock {
		UUID id();

		String kind();

		UUID parentId();

		UUID sectionId();

		List<SourceRef> sourceRefs();

		Extraction extraction();
	}

	// 제목이나 문단의 텍스트 데이터 구조
	public record TextBlock(
		@JsonProperty(required = true) UUID id,
		@JsonProperty(required = true) String kind,
		@JsonProperty(required = true) UUID parentId,
		@JsonProperty(required = true) UUID sectionId,
		@JsonProperty(required = true) String text,
		@JsonProperty(required = true) String sourceText,
		@JsonProperty(required = true) List<Unit> units,
		@JsonProperty(required = true) List<SourceRef> sourceRefs,
		@JsonInclude(JsonInclude.Include.NON_NULL) Extraction extraction
	) implements Block {
		public TextBlock {
			units = List.copyOf(units);
			sourceRefs = List.copyOf(sourceRefs);
		}
	}

	// 표 전체의 데이터 구조
	public record TableBlock(
		@JsonProperty(required = true) UUID id,
		@JsonProperty(required = true) String kind,
		@JsonProperty(required = true) UUID parentId,
		@JsonProperty(required = true) UUID sectionId,
		@JsonProperty(required = true) List<SourceRef> sourceRefs,
		@JsonInclude(JsonInclude.Include.NON_NULL) Extraction extraction,
		@JsonProperty(required = true) int rowCount,
		@JsonProperty(required = true) int columnCount,
		@JsonProperty(required = true) String structureStatus,
		@JsonProperty(required = true) List<Cell> cells,
		@JsonProperty(required = true) List<Slot> unresolvedSlots,
		@JsonProperty(required = true) HeaderRelations headerRelations,
		@JsonProperty(required = true) List<UnitNoteRef> unitNoteRefs
	) implements Block {
		public TableBlock {
			sourceRefs = List.copyOf(sourceRefs);
			cells = List.copyOf(cells);
			unresolvedSlots = List.copyOf(unresolvedSlots);
			unitNoteRefs = List.copyOf(unitNoteRefs);
		}
	}

	// 표 안의 셀 데이터 구조
	public record Cell(
		@JsonProperty(required = true) UUID id,
		@JsonProperty(required = true) int row,
		@JsonProperty(required = true) int column,
		@JsonProperty(required = true) int rowspan,
		@JsonProperty(required = true) int colspan,
		@JsonProperty(required = true) String text,
		@JsonProperty(required = true) String sourceText,
		@JsonProperty(required = true) List<Unit> units,
		@JsonProperty(required = true) List<SourceRef> sourceRefs,
		@JsonInclude(JsonInclude.Include.NON_NULL) Extraction extraction
	) {
		public Cell {
			units = List.copyOf(units);
			sourceRefs = List.copyOf(sourceRefs);
		}
	}

	// 분리된 문장이나 항목 하나의 데이터 구조
	public record Unit(
		@JsonProperty(required = true) UUID id,
		@JsonProperty(required = true) int start,
		@JsonProperty(required = true) int end,
		@JsonProperty(required = true) String text
	) {
	}

	// 파서가 전달한 텍스트 검색 제외 여부와 이미지 분석 필요 여부
	public record Extraction(
		@JsonProperty(required = true) boolean excludedFromRetrieval,
		@JsonProperty(required = true) boolean requiresVision
	) {
	}

	// 원본 추적을 위한 파서 ID와 위치 정보
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SourceRef(
		@JsonProperty(required = true) String parserBlockId,
		Integer parserRow,
		Integer parserColumn,
		PdfLocation location
	) {
	}

	// 원본 PDF에서 내용이 있던 페이지와 좌표 정보
	public record PdfLocation(
		@JsonProperty(required = true) String type,
		@JsonProperty(required = true) int page,
		@JsonProperty(required = true) List<Double> bbox,
		@JsonProperty(required = true) String precision
	) {
		public PdfLocation {
			bbox = List.copyOf(bbox);
		}
	}

	// 셀 구조를 확인하지 못한 표 위치 정보
	public record Slot(
		@JsonProperty(required = true) int row,
		@JsonProperty(required = true) int column
	) {
	}

	// 데이터 셀과 제목 셀의 연결 정보
	public record HeaderRelation(
		@JsonProperty(required = true) UUID cellId,
		@JsonProperty(required = true) List<UUID> headerCellIds
	) {
		public HeaderRelation {
			headerCellIds = List.copyOf(headerCellIds);
		}
	}

	// 제목 셀 연결 목록과 확인 상태 정보
	public record HeaderRelations(
		@JsonProperty(required = true) String status,
		@JsonProperty(required = true) List<HeaderRelation> items
	) {
		public HeaderRelations {
			items = List.copyOf(items);
		}
	}

	// 표의 단위 설명이 있는 텍스트 위치 정보 (예 - 단위: 천원)
	public record UnitNoteRef(
		@JsonProperty(required = true) String type,
		@JsonProperty(required = true) UUID id,
		@JsonProperty(required = true) int start,
		@JsonProperty(required = true) int end
	) {
	}

	// 섹션의 제목 블록과 상위 섹션 연결 정보
	public record Section(
		@JsonProperty(required = true) UUID id,
		@JsonProperty(required = true) UUID headingBlockId,
		@JsonProperty(required = true) UUID parentSectionId
	) {
	}

	// 경고가 가리키는 대상 정보
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Target(
		@JsonProperty(required = true) String type,
		UUID id,
		Integer page
	) {
	}

	// 처리 중 발생한 경고 정보
	public record Warning(
		@JsonProperty(required = true) String stage,
		@JsonProperty(required = true) String code,
		@JsonProperty(required = true) Target target,
		@JsonProperty(required = true) String message
	) {
	}
}
