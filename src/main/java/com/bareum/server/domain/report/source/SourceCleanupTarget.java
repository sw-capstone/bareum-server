package com.bareum.server.domain.report.source;

/** 트랜잭션 밖에서 삭제할 원본의 소유 기록과 객체 키, 버전 정보를 전달한다. */
public record SourceCleanupTarget(
	long id,
	boolean orphan,
	String objectKey,
	String versionId
) {
}
