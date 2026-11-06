package com.bareum.server.domain.report.controller;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import com.bareum.server.domain.report.dto.request.ReportUploadRequest;
import com.bareum.server.domain.report.dto.response.ReportUploadResponse;
import com.bareum.server.domain.report.dto.response.SourceDeletionResponse;
import com.bareum.server.global.security.LoginPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** 보고서 접수와 원본 삭제 API의 요청 형식과 응답 계약을 정의한다. */
@Tag(name = "Report", description = "보고서 접수와 원본 관리")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1/reports")
public interface ReportControllerDocs {
	@Operation(summary = "PDF 보고서 접수",
		description = "REPORT-002. PDF 1개와 PLAN 또는 RESULT를 받습니다. "
				+ "20,000,000바이트와 60페이지 이내이며 S3 저장, 파싱, 본문과 이력 저장이 끝난 뒤 응답합니다. "
				+ "PARTIAL도 성공이며 경고를 함께 반환합니다. 분석은 별도로 요청합니다. "
				+ "같은 사용자와 Idempotency-Key의 재전송은 저장된 결과를 재사용하며, 결과는 완료 후 최소 24시간 보관합니다. "
				+ "신규 접수는 Spring 인스턴스별 동시 처리 수를 제한하며, 용량 초과 시 S3 저장과 접수 기록 생성 없이 503을 반환합니다. "
				+ "이 경우 같은 멱등 키로 나중에 다시 접수할 수 있습니다.")
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ApiResponses({
		@ApiResponse(responseCode = "201",
			description = "접수와 본문 저장 완료",
			content = @Content(schema = @Schema(implementation = ReportUploadResponse.class))),
		@ApiResponse(responseCode = "400", description = "필수값, 파일 개수, 문서 유형 또는 요청 키 오류"),
		@ApiResponse(responseCode = "401", description = "인증 실패"),
		@ApiResponse(responseCode = "409", description = "동일 요청 처리 중, 요청 키 충돌 또는 취소한 접수"),
		@ApiResponse(responseCode = "413", description = "파일 용량 초과"),
		@ApiResponse(responseCode = "415", description = "미지원 파일 형식"),
		@ApiResponse(responseCode = "422", description = "페이지 초과, 암호화 또는 손상 PDF, 추출할 본문 없음"),
		@ApiResponse(responseCode = "500", description = "파서 내부 오류 또는 저장 실패"),
		@ApiResponse(responseCode = "502", description = "외부 통신 실패"),
		@ApiResponse(responseCode = "503", description = "일시적인 처리 용량 부족"),
		@ApiResponse(responseCode = "504", description = "응답 시간 초과")
	})
	ResponseEntity<?> upload(
		@Valid @ModelAttribute ReportUploadRequest request,
		@RequestHeader("Idempotency-Key") UUID requestKey,
		@Parameter(hidden = true) @AuthenticationPrincipal LoginPrincipal principal
	);

	@Operation(summary = "미사용 접수의 원본 삭제 요청",
		description = "본인 소유의 파일 접수를 취소하고 원본 삭제를 예약합니다. "
				+ "파싱 진행 중이거나 분석을 시작한 보고서는 409로 거절합니다. 등록 후 해당 접수로 분석을 시작할 수 없습니다. "
				+ "본문과 파싱 이력은 유지하며 실제 S3 삭제는 별도 작업에서 처리합니다.")
	@DeleteMapping("/{reportId}/source")
	@ApiResponses({
		@ApiResponse(responseCode = "202",
			description = "삭제 요청 등록 또는 이미 삭제 대기 중",
			content = @Content(schema = @Schema(implementation = SourceDeletionResponse.class))),
		@ApiResponse(responseCode = "204", description = "실제 삭제 완료", content = @Content),
		@ApiResponse(responseCode = "400", description = "보고서 식별자 형식 오류"),
		@ApiResponse(responseCode = "401", description = "인증 실패"),
		@ApiResponse(responseCode = "404", description = "대상이 없거나 다른 사용자의 보고서"),
		@ApiResponse(responseCode = "409", description = "파싱 중이거나 분석을 시작한 보고서")
	})
	ResponseEntity<SourceDeletionResponse> deleteSource(
		@PathVariable Long reportId,
		@Parameter(hidden = true) @AuthenticationPrincipal LoginPrincipal principal
	);
}
