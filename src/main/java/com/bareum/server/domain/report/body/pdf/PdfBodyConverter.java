package com.bareum.server.domain.report.body.pdf;

import static com.bareum.server.domain.report.body.BodyInput.*;
import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.bareum.server.domain.report.body.BodyAssembler;
import com.bareum.server.domain.report.body.BodyInput;
import com.bareum.server.domain.report.body.ReportBody;
import com.bareum.server.domain.report.enums.SourceFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 서버의 PDF 파서 응답을 공통 본문으로 바꾸는 클래스
 */
@RequiredArgsConstructor
public final class PdfBodyConverter {
	// PDF 파서 응답을 읽을 때, 잘못된 값이나 타입을 자동으로 바꾸지 않고 오류로 처리한다.
	private final JsonMapper mapper = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.build();

	private final BodyAssembler assembler;

	// PDF 파서 응답을 공통 본문으로 변환
	public ReportBody convertParsed(String json) {
		try {
			PdfExtraction parsed = mapper.readValue(json, PdfExtraction.class);
			if (parsed == null) {
				throw new ReportException(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT);
			}
			if (!"1.2.0".equals(parsed.schemaVersion())) {
				throw new ReportException(ReportErrorCode.UNSUPPORTED_PARSE_SCHEMA_VERSION);
			}
			if (parsed.document() == null
					|| parsed.document().pageCount() != parsed.pages().size()
					|| parsed.parser() == null || !parsed.parser().isObject()) {
				throw new ReportException(ReportErrorCode.INVALID_PARSE_DOCUMENT_METADATA);
			}
			List<Page> pages = parsed.pages()
					.stream()
					.map(page -> new Page(page.page(), page.width(), page.height(), "UNROTATED_PDF_POINTS"))
					.toList();
			List<BlockInput> blocks = new ArrayList<>();
			for (PdfExtraction.BlockData block : parsed.blocks()) {
				List<SourceRef> refs = sources(block);
				// 현재 블록의 종류가 표인지 확인
				if ("table".equals(block.kind())) {
					blocks.add(PdfTableInput.convert(block, refs));
				} else {
					blocks.add(new TextInput(block.id(), block.kind(), block.parentId(), block.sectionId(),
							block.text(), block.sourceText(), refs,
							new Extraction(block.excludedFromRetrieval(), block.requiresVision())));
				}
			}
			List<WarningInput> warnings = new ArrayList<>();
			for (PdfExtraction.WarningData warning : parsed.warnings()) {
				warnings.add(new WarningInput("extraction", warning.code(), target(warning, parsed.blocks()),
						warning.detail() == null || warning.detail().isBlank() ? warning.code() : warning.detail()));
			}
			for (BlockInput block : blocks) {
				if (block instanceof TableInput table && !table.unresolvedSlots().isEmpty()) {
					warnings.add(new WarningInput("composition", "TABLE_STRUCTURE_UNCERTAIN",
							new TargetInput("block", table.key(), null), "복원되지 않은 표 위치를 미확인으로 보존했습니다."));
				}
			}
			return assembler.assemble(new BodyInput(SourceFormat.PDF, pages, blocks, sections(parsed), warnings));
		} catch (JacksonException exception) {
			throw new ReportException(ReportErrorCode.INVALID_PARSE_RESPONSE_FORMAT);
		} catch (ReportException exception) {
			if (exception.getBaseErrorCode() == ReportErrorCode.INVALID_CONTENT) {
				throw new ReportException(ReportErrorCode.INVALID_PARSED_CONTENT);
			}
			throw exception;
		}
	}

	// 내용이 PDF의 어디에서 왔는지 기록
	private static List<SourceRef> sources(PdfExtraction.BlockData block) {
		if (block.sourceFragments().isEmpty()) {
			return List.of(new SourceRef(block.id(), null, null,
					new PdfLocation("pdf", block.page(), block.bbox(), "block")));
		}
		return block.sourceFragments()
				.stream()
				.map(fragment -> new SourceRef(block.id(), null, null,
						new PdfLocation("pdf", block.page(), fragment.bbox(), "block")))
				.toList();
	}

	// 섹션의 상하 관계 정리
	private static List<SectionInput> sections(PdfExtraction parsed) {
		Map<String, PdfExtraction.BlockData> blocks = new HashMap<>();
		parsed.blocks().forEach(block -> blocks.put(block.id(), block));
		Map<String, String> byHeading = new HashMap<>();
		parsed.sections().forEach(section -> byHeading.put(section.headBlockId(), section.sectionId()));
		List<SectionInput> result = new ArrayList<>();
		for (PdfExtraction.SectionData section : parsed.sections()) {
			var head = blocks.get(section.headBlockId());
			if (head == null) {
				throw new ReportException(ReportErrorCode.INVALID_PARSE_SECTION_REFERENCE);
			}
			String parent = head.parentId();
			String parentSection = null;
			var visited = new HashSet<String>();
			while (parent != null) {
				if (!visited.add(parent)) {
					throw new ReportException(ReportErrorCode.CYCLIC_PARSE_SECTION_REFERENCE);
				}
				if (byHeading.containsKey(parent)) {
					parentSection = byHeading.get(parent);
					break;
				}
				var ancestor = blocks.get(parent);
				if (ancestor == null) {
					throw new ReportException(ReportErrorCode.INVALID_PARSE_SECTION_REFERENCE);
				}
				parent = ancestor.parentId();
			}
			result.add(new SectionInput(section.sectionId(), section.headBlockId(), parentSection));
		}
		return result;
	}

	// 경고가 가리키는 대상 결정
	private static TargetInput target(PdfExtraction.WarningData warning, List<PdfExtraction.BlockData> blocks) {
		if (warning.row() != null && warning.col() != null && warning.bbox() != null) {
			List<String> matches = new ArrayList<>();
			for (var block : blocks) {
				if (!Integer.valueOf(block.page()).equals(warning.page())) {
					continue;
				}
				for (var cell : block.cells()) {
					if (cell.row() == warning.row() && cell.col() == warning.col()
							&& warning.bbox().equals(cell.bbox())) {
						matches.add(PdfTableInput.cellKey(block.id(), cell.row(), cell.col()));
					}
				}
			}
			if (matches.size() == 1) {
				return new TargetInput("cell", matches.getFirst(), null);
			}
		}
		return warning.page() == null ? new TargetInput("document", null, null)
				: new TargetInput("page", null, warning.page());
	}
}
