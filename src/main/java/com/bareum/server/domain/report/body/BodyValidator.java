package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bareum.server.domain.report.enums.BodyTargetType;
import com.bareum.server.domain.report.enums.SourceFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

/**
 * 완성된 본문(ReportBody)이 정해진 구조와 연결 규칙을 지켰는지 검사하는 클래스
 */
public final class BodyValidator {
	// 본문 전체 검사
	public void validate(ReportBody body) {
		// TODO: 검증 실패 위치를 추적할 수 있도록 내부 로그에 위치 정보를 연결한다.
		if (body == null || !"1.0.0".equals(body.schemaVersion())
				|| !"UNICODE_CODE_POINT".equals(body.offsetUnit()) || body.sourceFormat() == null) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		if (body.sourceFormat() == SourceFormat.TEXT ? !body.pages().isEmpty() : body.pages().isEmpty()) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		Map<Integer, Page> pages = new HashMap<>();
		for (Page page : body.pages()) {
			if (!(page.number() > 0 && Double.isFinite(page.width()) && page.width() > 0
					&& Double.isFinite(page.height()) && page.height() > 0
					&& "UNROTATED_PDF_POINTS".equals(page.coordinateSystem()))) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			if (pages.putIfAbsent(page.number(), page) != null) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
		}
		BodyIdRegistry registry = new BodyIdRegistry(body);
		Map<UUID, UUID> parents = new HashMap<>();
		for (Block block : body.blocks()) {
			if (block.lineCount() != null && block.lineCount() < 0) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			if (block.parentId() != null) {
				registry.reference(block.parentId(), BodyTargetType.BLOCK);
			}
			if (block.sectionId() != null) {
				registry.reference(block.sectionId(), BodyTargetType.SECTION);
			}
			parents.put(block.id(), block.parentId());
			sources(block.sourceRefs(), pages, false);
			// 표라면 추가 검사
			switch (block) {
				case TextBlock text -> {}
				case TableBlock table -> {
					BodyTableChecks.validate(table);
					for (Cell cell : table.cells()) {
						sources(cell.sourceRefs(), pages, true);
					}
					for (UnitNoteRef note : table.unitNoteRefs()) {
						BodyTargetType type = ownerType(note.type());
						registry.reference(note.id(), type);
						String text = registry.texts.get(note.id());
						if (text == null) {
							throw new ReportException(ReportErrorCode.INVALID_CONTENT);
						}
						BodyTextChecks.range(text, note.start(), note.end());
					}
				}
			}
		}
		// 부모 관계의 순환 검사
		cycles(parents);
		Map<UUID, UUID> sectionParents = new HashMap<>();
		for (Section section : body.sections()) {
			registry.reference(section.headingBlockId(), BodyTargetType.BLOCK);
			if (section.parentSectionId() != null) {
				registry.reference(section.parentSectionId(), BodyTargetType.SECTION);
			}
			sectionParents.put(section.id(), section.parentSectionId());
		}
		cycles(sectionParents);
		for (Warning warning : body.warnings()) {
			if (!present(warning.stage()) || !present(warning.code()) || !present(warning.message())
					|| warning.target() == null) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			Target target = warning.target();
			if (!present(target.type())) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			switch (target.type()) {
				case "document" -> {
					if (target.id() != null || target.page() != null) {
						throw new ReportException(ReportErrorCode.INVALID_CONTENT);
					}
				}
				case "page" -> {
					if (target.id() != null || !pages.containsKey(target.page())) {
						throw new ReportException(ReportErrorCode.INVALID_CONTENT);
					}
				}
				case "block", "cell", "unit", "section" -> {
					if (target.page() != null) {
						throw new ReportException(ReportErrorCode.INVALID_CONTENT);
					}
					registry.reference(target.id(), switch (target.type()) {
						case "block" -> BodyTargetType.BLOCK;
						case "cell" -> BodyTargetType.CELL;
						case "unit" -> BodyTargetType.UNIT;
						case "section" -> BodyTargetType.SECTION;
						default -> throw new ReportException(ReportErrorCode.INVALID_CONTENT);
					});
				}
				default -> throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
		}
	}

	// 단위 표기 대상의 종류 확인
	private static BodyTargetType ownerType(String type) {
		if (!present(type)) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		return switch (type) {
			case "block" -> BodyTargetType.BLOCK;
			case "cell" -> BodyTargetType.CELL;
			default -> throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		};
	}

	// 원본 춡처와 PDF 좌표 검사
	private static void sources(List<SourceRef> refs, Map<Integer, Page> pages, boolean cell) {
		for (SourceRef ref : refs) {
			if (!present(ref.parserBlockId()) || (ref.parserRow() == null) != (ref.parserColumn() == null)
					|| (cell && ref.parserRow() == null)) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			if (ref.parserRow() != null && (ref.parserRow() < 0 || ref.parserColumn() < 0)) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			PdfLocation location = ref.location();
			if (location == null) {
				continue;
			}
			if (!"pdf".equals(location.type()) || !present(location.precision())
					|| !Set.of("block", "cell").contains(location.precision())) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			Page page = pages.get(location.page());
			List<Double> box = location.bbox();
			if (page == null || box.size() != 4 || !box.stream().allMatch(Double::isFinite)) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			if (!(box.get(0) >= 0 && box.get(1) >= 0 && box.get(0) < box.get(2) && box.get(1) < box.get(3)
					&& box.get(2) <= page.width() && box.get(3) <= page.height())) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
		}
	}

	// 빈 값 확인
	private static boolean present(String value) { return value != null && !value.isBlank(); }

	// 부모 관계의 순환 검사
	private static void cycles(Map<UUID, UUID> parents) {
		Set<UUID> finished = new HashSet<>();
		for (UUID start : parents.keySet()) {
			Set<UUID> path = new HashSet<>();
			UUID current = start;
			while (current != null && !finished.contains(current)) {
				if (!path.add(current)) {
					throw new ReportException(ReportErrorCode.INVALID_CONTENT);
				}
				current = parents.get(current);
			}
			finished.addAll(path);
		}
	}
}
