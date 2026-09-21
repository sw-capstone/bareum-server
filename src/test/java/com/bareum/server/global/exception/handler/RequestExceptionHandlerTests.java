package com.bareum.server.global.exception.handler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestExceptionHandlerTests {

	private MockMvc mockMvc;
	private Logger logger;
	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
		logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@ParameterizedTest
	@ValueSource(strings = {"json", "body", "missing", "type", "method", "media"})
	void requestErrorsUsePublicCodesAndPreserveHeaders(String scenario) throws Exception {
		MockHttpServletRequestBuilder request;
		String code;
		String detail;
		int expectedStatus = 400;
		switch (scenario) {
			case "json", "body" -> {
				request = post("/test/validate").contentType(MediaType.APPLICATION_JSON)
						.content(scenario.equals("json") ? "{private-invalid" : "");
				code = "COMMON-0003";
				detail = "요청 본문의 형식과 필수 내용을 확인해주세요.";
			}
			case "missing" -> {
				request = get("/test/query-validation");
				code = "COMMON-0004";
				detail = "필수 요청 값이 누락되었습니다.";
			}
			case "type" -> {
				request = get("/test/query-validation").param("page_size", "private-invalid");
				code = "COMMON-0005";
				detail = "요청 값의 형식이 올바르지 않습니다.";
			}
			case "method" -> {
				request = post("/test/success");
				code = "COMMON-0006";
				detail = "지원하지 않는 HTTP 메서드입니다.";
				expectedStatus = 405;
			}
			case "media" -> {
				request = post("/test/validate").contentType(MediaType.TEXT_PLAIN).content("private-invalid");
				code = "COMMON-0007";
				detail = "지원하지 않는 요청 데이터 형식입니다.";
				expectedStatus = 415;
			}
			default -> throw new IllegalArgumentException(scenario);
		}
		mockMvc.perform(request.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().is(expectedStatus))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(expectedStatus))
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.detail").value(detail))
				.andExpect(jsonPath("$.errors").doesNotExist())
				.andExpect(result -> {
					assertThat(result.getResponse().getContentAsString()).doesNotContain("private-invalid");
					if (scenario.equals("method")) {
						assertThat(result.getResponse().getHeader("Allow")).contains("GET");
					}
					if (scenario.equals("media")) {
						assertThat(result.getResponse().getHeader("Accept")).contains("application/json");
					}
				});
		assertThat(appender.list).isEmpty();
	}

	@RestController
	static class TestController {

		@PostMapping("/test/validate")
		ResponseEntity<Void> validate(@Valid @RequestBody TestRequest request) {
			return ResponseEntity.noContent().build();
		}

		@GetMapping("/test/query-validation")
		ResponseEntity<Void> queryValidation(
				@RequestParam("page_size") @Min(value = 1, message = "크기는 1 이상이어야 합니다.") int size) {
			return ResponseEntity.noContent().build();
		}

		@GetMapping("/test/success")
		ResponseEntity<TestResponse> success() {
			return ResponseEntity.ok(new TestResponse(1L, "example"));
		}
	}

	record TestRequest(
			@Email(message = "올바른 이메일 형식을 입력해주세요.") String email,
			@NotBlank(message = "이름을 입력해주세요.") String name) {
	}

	record TestResponse(Long id, String name) {
	}
}
