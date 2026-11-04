package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.bareum.server.domain.report.config.ReportUploadConfig;
import com.bareum.server.domain.report.config.ReportUploadProperties;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.parsing.PdfParser;
import com.bareum.server.domain.report.source.ReportSourceStore;
import com.bareum.server.global.config.TimeConfig;

/** 외부 연결 설정의 누락 처리와 제한값 검증을 확인한다. */
class ReportUploadConfigurationTests {
	@Test
	void missingDeploymentSettingsKeepContextAvailableAndRejectOnlyUploads() {
		new ApplicationContextRunner().withUserConfiguration(ReportUploadConfig.class, TimeConfig.class)
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertNotNull(context.getBean(ReportSourceStore.class));
					var parser = context.getBean(PdfParser.class);
					assertEquals(ReportErrorCode.UPLOAD_NOT_CONFIGURED,
							assertThrows(ReportException.class, () -> parser.parse(null)).getBaseErrorCode());
				});
	}

	@Test
	void deploymentSettingsMustLeaveRoomForProxyAndSignedUrlDeadlines() {
		assertDoesNotThrow(() -> properties(600, 310, 30).requireConfigured());
		assertThrows(ReportException.class, () -> properties(100, 310, 30).requireConfigured());
		assertThrows(ReportException.class, () -> properties(600, 360, 30).requireConfigured());
		assertThrows(ReportException.class, () -> properties(600, 310, 60).requireConfigured());
		assertFalse(properties(600, 310, 30).toString().contains("fixture-token"));
	}

	private ReportUploadProperties properties(int signed, int parser, int storage) {
		return new ReportUploadProperties("fixture-bucket", "ap-northeast-2", "reports/",
				"http://127.0.0.1/internal/v1/pdf-parses", "fixture-token", signed, parser, storage);
	}
}
