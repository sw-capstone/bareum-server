package com.bareum.server.domain.report.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

/** 보고서 접수에 사용할 저장소와 파싱 서버의 연결 설정을 검증한다. */
@ConfigurationProperties("report.upload")
public record ReportUploadProperties(
	String bucket,
	@DefaultValue("ap-northeast-2") String region,
	@DefaultValue("reports/") String keyPrefix,
	String parserEndpoint,
	String parserToken,
	@DefaultValue("600") int signedUrlSeconds,
	@DefaultValue("310") int parserTimeoutSeconds,
	@DefaultValue("30") int storageTimeoutSeconds
) {
	public void requireConfigured() {
		// AI와 같은 virtual-hosted HTTPS 주소를 사용하므로 점이 있는 버킷명은 받지 않는다.
		if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
				|| region == null || !region.matches("[a-z]{2}(-[a-z]+)+-[0-9]+")
				|| keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*/")
				|| parserToken == null || parserToken.isBlank()
				|| parserToken.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))
				|| parserTimeoutSeconds < 1 || parserTimeoutSeconds > 320
				|| storageTimeoutSeconds < 1 || storageTimeoutSeconds > 30
				|| signedUrlSeconds < parserTimeoutSeconds + 30 || signedUrlSeconds > 3600) {
			throw new ReportException(ReportErrorCode.UPLOAD_NOT_CONFIGURED);
		}
		try {
			URI endpoint = URI.create(parserEndpoint);
			if (!endpoint.isAbsolute() || endpoint.getHost() == null || endpoint.getUserInfo() != null
					|| endpoint.getFragment() != null || endpoint.getQuery() != null
					|| !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new ReportException(ReportErrorCode.UPLOAD_NOT_CONFIGURED);
		}
	}

	@Override
	public String toString() {
		return "ReportUploadProperties[credentials=REDACTED]";
	}
}
