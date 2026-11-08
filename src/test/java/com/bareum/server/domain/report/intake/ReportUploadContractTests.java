package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;

import com.bareum.server.domain.report.body.ReportBody.Target;
import com.bareum.server.domain.report.body.ReportBody.Warning;
import com.bareum.server.domain.report.controller.ReportControllerDocs;
import com.bareum.server.domain.report.dto.request.ReportUploadRequest;
import com.bareum.server.domain.report.dto.response.ReportUploadResponse;
import com.bareum.server.domain.report.dto.response.SourceDeletionResponse;
import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.enums.ParseStatus;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;
import com.bareum.server.global.security.LoginPrincipal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 보고서 접수 API의 요청 바인딩과 공개 응답 계약을 검증한다. */
class ReportUploadContractTests {
	private static final Path CONTRACT = Path.of("packages/contracts/report-intake/1.0.0");
	private final JsonMapper mapper = JsonMapper.builder().build();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new ContractController())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void multipartContractBindsFileTypeAndUuidHeader() throws Exception {
		mvc.perform(multipart("/api/v1/reports").file(file())
				.param("reportType", "PLAN")
				.header("Idempotency-Key", UUID.randomUUID()))
				.andExpect(status().isCreated())
				.andExpect(content().json(example("success").toString()));
	}

	@Test
	void reportTypeFileAndValidRequestKeyAreMandatory() throws Exception {
		mvc.perform(multipart("/api/v1/reports").file(file()).header("Idempotency-Key", UUID.randomUUID()))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/v1/reports").param("reportType", "PLAN")
				.header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/v1/reports").file(file()).param("reportType", "PLAN"))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/v1/reports").file(file())
				.param("reportType", "PLAN")
				.header("Idempotency-Key", "not-a-uuid")).andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/v1/reports").file(file())
				.param("reportType", "계획 보고서")
				.header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isBadRequest());
	}

	@Test
	void partialSuccessPreservesStructuredWarningsInThePublicExample() throws Exception {
		var warning = new Warning("extraction", "table_detection_failed", new Target("page", null, 2),
				"2페이지의 표 구조를 추출하지 못했습니다.");
		var response = response(ExtractionQuality.PARTIAL, List.of(warning));
		assertEquals(example("partial"), mapper.readTree(mapper.writeValueAsString(response)));
		assertThrows(UnsupportedOperationException.class, () -> response.warnings().clear());
	}

	@Test
	void deletionContractReturnsAcceptedOrNoContent() throws Exception {
		mvc.perform(delete("/api/v1/reports/101/source"))
				.andExpect(status().isAccepted())
				.andExpect(content().json(example("source-deletion").toString()));
		mvc.perform(delete("/api/v1/reports/102/source"))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
	}

	@Test
	void openApiPublishesTheAgreedLimitsAndNoPublicParsingRetryEndpoint() throws Exception {
		JsonNode api = mapper.readTree(Files.readString(CONTRACT.resolve("openapi.json")));
		assertEquals(2, api.get("paths").size());
		assertEquals(20_000_000, api.get("x-server-policy").get("maxFileSizeBytes").asLong());
		assertEquals(ReportIntakePolicy.MAX_PAGE_COUNT, api.get("x-server-policy").get("maxPageCount").asInt());
		assertEquals(1, api.get("x-server-policy").get("maxAdditionalParseAttempts").asInt());
		assertTrue(api.get("paths").get("/reports").get("post").get("responses").has("201"));
		assertTrue(api.get("paths").get("/reports/{reportId}/source").get("delete").get("responses").has("204"));
	}

	private JsonNode example(String name) throws Exception {
		return mapper.readTree(Files.readString(CONTRACT.resolve("examples/" + name + ".example.json")));
	}

	private MockMultipartFile file() {
		return new MockMultipartFile("file", "report.pdf", "application/pdf", "%PDF-1.7".getBytes());
	}

	private static ReportUploadResponse response(ExtractionQuality quality, List<Warning> warnings) {
		return new ReportUploadResponse(101L, 301L, 201L, ParseStatus.SUCCEEDED, quality, warnings,
				Instant.parse("2026-12-04T03:00:00Z"));
	}

	/** 요청 바인딩과 응답 계약을 검증할 테스트 컨트롤러다. */
	@RestController
	@Profile("report-intake-contract-fixture")
	static class ContractController implements ReportControllerDocs {
		@Override
		public ResponseEntity<ReportUploadResponse> upload(
			ReportUploadRequest request,
			UUID key,
			LoginPrincipal principal
		) {
			return ResponseEntity.status(201).body(response(ExtractionQuality.COMPLETE, List.of()));
		}

		@Override
		public ResponseEntity<SourceDeletionResponse> deleteSource(Long reportId, LoginPrincipal principal) {
			return reportId == 102 ? ResponseEntity.noContent().build()
					: ResponseEntity.accepted().body(SourceDeletionResponse.pending(reportId));
		}
	}
}
