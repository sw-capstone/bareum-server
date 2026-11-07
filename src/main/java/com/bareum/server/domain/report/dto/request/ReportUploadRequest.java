package com.bareum.server.domain.report.dto.request;

import org.springframework.web.multipart.MultipartFile;

import com.bareum.server.domain.report.enums.ReportType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 접수할 PDF 파일과 필수 문서 유형을 전달한다. */
public record ReportUploadRequest(
	@Schema(description = "PDF 원본 1개. 최대 20,000,000바이트, 60페이지",
		type = "string",
		format = "binary") @NotNull MultipartFile file,
	@Schema(description = "접수 시 저장하고 이후 변경하지 않는 문서 유형") @NotNull ReportType reportType
) {
}
