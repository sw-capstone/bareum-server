package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.BodyInput.*;
import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import lombok.RequiredArgsConstructor;

/**
 * 입력 데이터를 조합하고 검증해 공통 본문을 만드는 클래스
 */
@RequiredArgsConstructor
public final class BodyAssembler {
	private final TextSegmenter textSegmenter;
	private final Supplier<UUID> ids; // 호출할 때 UUID 제공하는 함수
	private final BodyValidator validator = new BodyValidator();

	public BodyAssembler(TextSegmenter textSegmenter) { this(textSegmenter, UUID::randomUUID); }

	// 전체 조립 담당
	// TODO(#53): 접수 서비스에서 본문 구성과 본문 및 파싱 이력의 DB 저장 흐름을 연결한다.
	public ReportBody assemble(BodyInput input) {
		// 입력 키와 서비스 UUID를 연결
		Map<String, UUID> mapped = new HashMap<>();
		for (BlockInput block : input.blocks()) {
			allocate(mapped, block.key());
			switch (block) {
				case TextInput text -> {}
				case TableInput table -> {
					for (CellInput cell : table.cells()) {
						allocate(mapped, cell.key());
					}
				}
			}
		}
		for (SectionInput section : input.sections()) {
			allocate(mapped, section.key());
		}
		// 입력 경고의 대상 키를 서비스 UUID로 변환해 본문 경고 목록에 추가
		List<Warning> warnings = new ArrayList<>();
		for (WarningInput warning : input.warnings()) {
			TargetInput target = warning.target();
			warnings.add(new Warning(warning.stage(), warning.code(),
					new Target(target.type(), resolve(mapped, target.key()), target.page()), warning.message()));
		}
		// 입력 블록을 서비스 UUID와 문장 분리 결과가 포함된 본문 블록으로 변환
		List<Block> blocks = new ArrayList<>();
		for (BlockInput block : input.blocks()) {
			UUID id = resolve(mapped, block.key());
			UUID parent = resolve(mapped, block.parentKey());
			UUID section = resolve(mapped, block.sectionKey());
			switch (block) {
				case TextInput text -> blocks.add(new TextBlock(id, text.kind(), parent, section,
						text.text(), text.sourceText(), units(text.text(), new Target("block", id, null), warnings),
						text.sourceRefs(), text.extraction()));
				case TableInput table -> {
					List<Cell> cells = table.cells().stream().map(cell -> {
						UUID cellId = resolve(mapped, cell.key());
						return new Cell(cellId, cell.row(), cell.column(), cell.rowspan(), cell.colspan(),
								cell.text(), cell.sourceText(),
								units(cell.text(), new Target("cell", cellId, null), warnings),
								cell.sourceRefs(), cell.extraction());
					}).toList();
					// 머리글 연결 정보 변환
					List<HeaderRelation> headers = table.headers()
							.stream()
							.map(header -> new HeaderRelation(resolve(mapped, header.cellKey()),
									header.headerKeys().stream().map(key -> resolve(mapped, key)).toList()))
							.toList();
					// 단위 표기의 위치 정보 변환. 표에 붙은 (단위: 천원) 같은 표기가 어디에 있는지 연결
					List<UnitNoteRef> notes = table.unitNotes()
							.stream()
							.map(note -> new UnitNoteRef(note.type(), resolve(mapped, note.key()), note.start(),
									note.end()))
							.toList();
					// 완성된 표 블록 추가
					blocks.add(new TableBlock(id, table.kind(), parent, section, table.sourceRefs(),
							table.extraction(), table.rowCount(), table.columnCount(), table.structureStatus(),
							cells, table.unresolvedSlots(), new HeaderRelations(table.headerStatus(), headers), notes));
				}
			}
		}
		List<Section> sections = input.sections()
				.stream()
				.map(section -> new Section(
						resolve(mapped, section.key()), resolve(mapped, section.headingKey()),
						resolve(mapped, section.parentKey())))
				.toList();
		ReportBody body = new ReportBody("1.0.0", "UNICODE_CODE_POINT", input.sourceFormat(),
				input.pages(), blocks, sections, warnings);
		validator.validate(body);
		return body;
	}

	// TextSegmenter로 텍스트를 분리하고, 경계가 불확실하면 해당 블록이나 셀을 대상으로 경고 추가
	private List<Unit> units(String text, Target target, List<Warning> warnings) {
		TextSegmenter.Result result = textSegmenter.split(text);
		if (result.uncertain()) {
			warnings.add(new Warning("segmentation", "BOUNDARY_UNCERTAIN", target,
					"문장 경계가 불명확한 구간을 원문 그대로 유지했습니다."));
		}
		return result.units();
	}

	// 키에 UUID 매핑. 키가 없거나 공백이거나 중복이면 에러
	private void allocate(Map<String, UUID> mapped, String key) {
		if (key == null || key.isBlank() || mapped.putIfAbsent(key, ids.get()) != null) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
	}

	// 키에 연결된 UUID 조회. 키가 null이면 null을 반환하고, 키가 있는데 등록되어 있지 않으면 에러
	private static UUID resolve(Map<String, UUID> mapped, String key) {
		if (key == null) {
			return null;
		}
		UUID id = mapped.get(key);
		if (id == null) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		return id;
	}
}
