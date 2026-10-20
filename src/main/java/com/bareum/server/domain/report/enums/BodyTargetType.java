package com.bareum.server.domain.report.enums;

/**
 * 본문 내부의 UUID가 어떤 종류의 대상을 가리키는지 구분한다.
 */
public enum BodyTargetType {
	BLOCK,   // 문단·제목·표
	CELL,    // 표 안의 셀
	UNIT,    // 문단·셀에서 분리한 텍스트 단위
	SECTION  // 제목과 관련 블록을 묶는 문서 구획
}
