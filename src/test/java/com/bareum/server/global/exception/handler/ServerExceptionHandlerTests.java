package com.bareum.server.global.exception.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ServerExceptionHandlerTests {

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

	@Test
	void unexpectedExceptionHidesInternalMessageAndLogsStackOnce() throws Exception {
		mockMvc.perform(get("/test/unexpected"))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Internal Server Error"))
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.code").value("COMMON-0001"))
				.andExpect(jsonPath("$.detail").value("서버 오류가 발생했습니다. 관리자에게 문의해주세요."))
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.doesNotContain("internal-failure"));
		assertThat(appender.list).hasSize(1);
		assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
		assertThat(appender.list.getFirst().getThrowableProxy().getClassName())
				.isEqualTo(IllegalStateException.class.getName());
	}

	@Test
	void illegalArgumentIsNotAutomaticallyTreatedAsBadRequest() throws Exception {
		mockMvc.perform(get("/test/illegal-argument"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON-0001"));
	}

	@Test
	void returnValueValidationIsServerErrorWithoutValidationDetails() throws Exception {
		mockMvc.perform(get("/test/return-validation"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON-0001"))
				.andExpect(jsonPath("$.errors").doesNotExist())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.doesNotContain("private-return-validation"));
		assertThat(appender.list).hasSize(1);
		assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
	}

	@ParameterizedTest
	@ValueSource(strings = {"serialization", "conversion"})
	void springServerErrorsUseCommonResponseAndLogCause(String scenario) throws Exception {
		mockMvc.perform(get("/test/" + scenario).accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.code").value("COMMON-0001"))
				.andExpect(jsonPath("$.detail").value("서버 오류가 발생했습니다. 관리자에게 문의해주세요."))
				.andExpect(jsonPath("$.errors").doesNotExist())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.doesNotContain("private-"));
		assertThat(appender.list).hasSize(1);
		assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
		assertThat(appender.list.getFirst().getThrowableProxy()).isNotNull();
		assertThat(appender.list.getFirst().getThrowableProxy().getCause()).isNotNull();
	}

	@RestController
	static class TestController {

		@GetMapping("/test/unexpected")
		ResponseEntity<TestResponse> unexpected() {
			throw new IllegalStateException("internal-failure");
		}

		@GetMapping("/test/illegal-argument")
		ResponseEntity<TestResponse> illegalArgument() {
			throw new IllegalArgumentException("internal-argument");
		}

		@GetMapping("/test/return-validation")
		@Min(value = 1, message = "private-return-validation")
		int returnValidation() {
			return 0;
		}

		@GetMapping("/test/serialization")
		ResponseEntity<BrokenResponse> serialization() {
			return ResponseEntity.ok(new BrokenResponse());
		}

		@GetMapping("/test/conversion")
		ResponseEntity<TestResponse> conversion() {
			throw new ConversionNotSupportedException("private-value", TestResponse.class,
					new IllegalStateException("private-conversion-cause"));
		}
	}

	record TestResponse(Long id, String name) {
	}

	static class BrokenResponse {
		public String getValue() {
			throw new IllegalStateException("private-serialization-cause");
		}
	}
}
