package com.bareum.server.domain.report.body.pdf;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/**
 * AI 서버의 PDF 파서 응답을 Java에서 다루기 위한 데이터 구조
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PdfExtraction(
	@JsonProperty(value = "schema_version", required = true) String schemaVersion,
	@JsonProperty(required = true) Document document,
	@JsonProperty(required = true) JsonNode parser,
	@JsonProperty(required = true) List<PageData> pages,
	@JsonProperty(required = true) List<SectionData> sections,
	@JsonProperty(required = true) List<BlockData> blocks,
	@JsonProperty(value = "layout_regions", required = true) List<JsonNode> layoutRegions,
	@JsonProperty(required = true) List<WarningData> warnings
) {
	public PdfExtraction {
		pages = List.copyOf(pages);
		sections = List.copyOf(sections);
		blocks = List.copyOf(blocks);
		layoutRegions = List.copyOf(layoutRegions);
		warnings = List.copyOf(warnings);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Document(
		@JsonProperty(value = "page_count", required = true) int pageCount,
		@JsonProperty(required = true) String sha256
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PageData(
		@JsonProperty(required = true) int page,
		@JsonProperty(required = true) double width,
		@JsonProperty(required = true) double height
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Fragment(
		List<Double> bbox
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CellData(
		@JsonProperty(required = true) int row,
		@JsonProperty(required = true) int col,
		@JsonProperty(required = true) int rowspan,
		@JsonProperty(required = true) int colspan,
		List<Double> bbox
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BlockData(
		@JsonProperty(required = true) String id,
		@JsonProperty(required = true) int page,
		@JsonProperty(required = true) String kind,
		@JsonProperty(required = true) String text,
		@JsonProperty(value = "source_text", required = true) String sourceText,
		@JsonProperty(value = "line_count", required = true) int lineCount,
		@JsonProperty(required = true) List<Double> bbox,
		@JsonProperty("parent_id") String parentId,
		@JsonProperty("section_id") String sectionId,
		@JsonProperty("excluded_from_retrieval") Boolean excludedFromRetrieval,
		@JsonProperty("requires_vision") Boolean requiresVision,
		List<List<String>> rows,
		@JsonProperty("source_rows") List<List<String>> sourceRows,
		List<CellData> cells,
		@JsonProperty("source_fragments") List<Fragment> sourceFragments
	) {
		public BlockData {
			excludedFromRetrieval = Boolean.TRUE.equals(excludedFromRetrieval);
			requiresVision = Boolean.TRUE.equals(requiresVision);
			bbox = List.copyOf(bbox);
			rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
			sourceRows = sourceRows == null ? List.of() : sourceRows.stream().map(List::copyOf).toList();
			cells = cells == null ? List.of() : List.copyOf(cells);
			sourceFragments = sourceFragments == null ? List.of() : List.copyOf(sourceFragments);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SectionData(
		@JsonProperty(value = "section_id", required = true) String sectionId,
		@JsonProperty(value = "head_block_id", required = true) String headBlockId
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record WarningData(
		@JsonProperty(required = true) String code,
		String detail,
		Integer page,
		List<Double> bbox,
		Integer row,
		Integer col
	) {
	}
}
