package com.bareum.server.domain.report.body;

import static com.bareum.server.domain.report.body.ReportBody.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bareum.server.domain.report.enums.BodyTargetType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

/**
 * 본문 내부의 ID와 텍스트를 모아 두고, ID 중복과 잘못된 참조를 검사하는 보조 클래스
 */
final class BodyIdRegistry {
	final Map<UUID, BodyTargetType> types = new HashMap<>();
	// TODO: Unit 반복 조회가 필요해지면 소속 블록과 셀 위치도 찾는 구조로 확장을 검토한다.
	final Map<UUID, String> texts = new HashMap<>();

	// 본문 요소의 ID를 등록하고 블록 종류와 텍스트를 검증한다.
	BodyIdRegistry(ReportBody body) {
		for (Block block : body.blocks()) {
			add(block.id(), BodyTargetType.BLOCK);
			if (block.kind() == null || block.kind().isBlank()) {
				throw new ReportException(ReportErrorCode.INVALID_CONTENT);
			}
			switch (block) {
				case TextBlock text -> {
					if ("table".equals(text.kind())) {
						throw new ReportException(ReportErrorCode.INVALID_CONTENT);
					}
					text(text.id(), text.text(), text.sourceText(), text.units());
				}
				case TableBlock table -> {
					if (!"table".equals(table.kind())) {
						throw new ReportException(ReportErrorCode.INVALID_CONTENT);
					}
					for (Cell cell : table.cells()) {
						add(cell.id(), BodyTargetType.CELL);
						text(cell.id(), cell.text(), cell.sourceText(), cell.units());
					}
				}
			}
		}
		for (Section section : body.sections()) {
			add(section.id(), BodyTargetType.SECTION);
		}
	}
	// 텍스트를 검증하고 소속 텍스트와 내부 단위의 ID를 등록
	private void text(UUID id, String text, String sourceText, List<Unit> units) {
		BodyTextChecks.validate(text, sourceText, units);
		texts.put(id, text);
		for (Unit unit : units) {
			add(unit.id(), BodyTargetType.UNIT);
		}
	}
	// ID의 누락 및 중복을 검사하고 대상 종류를 등록
	private void add(UUID id, BodyTargetType type) {
		if (id == null || types.putIfAbsent(id, type) != null) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
	}
	// 참조 ID가 존재하고 기대한 대상 종류와 일치하는지 검사
	void reference(UUID id, BodyTargetType type) {
		if (id == null || types.get(id) != type) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
	}
}
