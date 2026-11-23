package com.bareum.server.domain.report.source;

/** 트랜잭션 밖에서 정리할 파일 또는 고아 원본 접수의 ID와 S3 객체 정보를 전달한다. */
public record SourceCleanupTarget(
	long id,
	boolean orphan,
	String objectKey,
	String versionId
) {
}
