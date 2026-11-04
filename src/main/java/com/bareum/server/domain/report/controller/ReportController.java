package com.bareum.server.domain.report.controller;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.bareum.server.domain.report.dto.request.ReportUploadRequest;
import com.bareum.server.domain.report.dto.response.SourceDeletionResponse;
import com.bareum.server.domain.report.service.ReportUploadService;
import com.bareum.server.domain.report.service.command.ReportSourceCommandService;
import com.bareum.server.global.security.LoginPrincipal;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 보고서 접수와 원본 삭제 요청을 받아 처리 결과를 HTTP 응답으로 반환한다. */
@RestController
@RequiredArgsConstructor
public class ReportController implements ReportControllerDocs {
	private final ReportUploadService uploads;
	private final ReportSourceCommandService deletions;

	@Override
	public ResponseEntity<JsonNode> upload(ReportUploadRequest request, UUID requestKey, LoginPrincipal principal) {
		var result = uploads.upload(principal.memberId(), requestKey, request.reportType(), request.file());
		return ResponseEntity.status(result.status())
				.contentType(result.status() >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON)
				.body(result.body());
	}

	@Override
	public ResponseEntity<SourceDeletionResponse> deleteSource(Long reportId, LoginPrincipal principal) {
		return deletions.request(principal.memberId(), reportId)
				? ResponseEntity.noContent().build()
				: ResponseEntity.accepted().body(SourceDeletionResponse.pending(reportId));
	}
}
