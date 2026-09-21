package com.bareum.server.global.exception.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bareum.server.global.exception.BusinessException;
import com.bareum.server.global.exception.Domain;
import com.bareum.server.global.exception.code.BaseErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

class BusinessExceptionHandlerTests {

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
	void businessExceptionReturnsPublicProblemAndLogsOnceWithoutStack() throws Exception {
		mockMvc.perform(get("/test/business").queryParam("token", "private-query"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Not Found"))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("대상을 찾을 수 없습니다."))
				.andExpect(jsonPath("$.instance").value("/test/business"))
				.andExpect(jsonPath("$.code").value("DOCUMENT-0001"))
				.andExpect(jsonPath("$.errors").doesNotExist())
				.andExpect(jsonPath("$.domain").doesNotExist())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.doesNotContain("internal-cause", "private-query"));
		assertThat(appender.list).hasSize(1);
		assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.INFO);
		assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
		assertThat(appender.list.getFirst().getFormattedMessage()).contains("domain=DOCUMENT", "code=DOCUMENT-0001")
				.doesNotContain("internal-cause", "private-query");
	}

	@Test
	void customMessageIsPublicDetailWithoutChangingCodeOrStatus() throws Exception {
		mockMvc.perform(get("/test/custom-message"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("DOCUMENT-0001"))
				.andExpect(jsonPath("$.detail").value("요청한 문서가 삭제되었습니다."))
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.doesNotContain("private-cause"));
		assertThat(appender.list.getFirst().getFormattedMessage())
				.doesNotContain("요청한 문서가 삭제되었습니다.", "private-cause");
	}

	@Test
	void warningIsSelectedByErrorCodeNotHttpStatus() throws Exception {
		mockMvc.perform(get("/test/warning"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("DOCUMENT-0002"));
		assertThat(appender.list).hasSize(1);
		assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
		assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
	}

	@Test
	void errorBusinessExceptionLogsCauseWithoutExposingIt() throws Exception {
		mockMvc.perform(get("/test/business-error"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("DOCUMENT-0003"))
				.andExpect(jsonPath("$.detail").value("대상을 찾을 수 없습니다."))
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.doesNotContain("private-business-cause", "IllegalStateException", "stackTrace"));
		assertThat(appender.list).hasSize(1);
		ILoggingEvent event = appender.list.getFirst();
		assertThat(event.getLevel()).isEqualTo(Level.ERROR);
		assertThat(event.getFormattedMessage()).contains("domain=DOCUMENT", "code=DOCUMENT-0003");
		assertThat(event.getThrowableProxy().getCause().getMessage()).isEqualTo("private-business-cause");
		assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
	}

	@RestController
	static class TestController {

		@GetMapping("/test/business")
		ResponseEntity<TestResponse> business() {
			throw new TestBusinessException(TestErrorCode.NOT_FOUND,
					new IllegalStateException("internal-cause"));
		}

		@GetMapping("/test/custom-message")
		ResponseEntity<TestResponse> customMessage() {
			throw new TestBusinessException(TestErrorCode.NOT_FOUND, "요청한 문서가 삭제되었습니다.",
					new IllegalStateException("private-cause"));
		}

		@GetMapping("/test/warning")
		ResponseEntity<TestResponse> warning() {
			throw new TestBusinessException(TestErrorCode.WARNING, "추가 확인이 필요합니다.");
		}

		@GetMapping("/test/business-error")
		ResponseEntity<TestResponse> businessError() {
			throw new TestBusinessException(TestErrorCode.ERROR,
					new IllegalStateException("private-business-cause"));
		}
	}

	record TestResponse(Long id, String name) {
	}

	static class TestBusinessException extends BusinessException {

		TestBusinessException(TestErrorCode errorCode, String message) {
			super(Domain.DOCUMENT, errorCode, message);
		}

		TestBusinessException(TestErrorCode errorCode, String message, Throwable cause) {
			super(Domain.DOCUMENT, errorCode, message, cause);
		}

		TestBusinessException(TestErrorCode errorCode, Throwable cause) {
			super(Domain.DOCUMENT, errorCode, cause);
		}
	}

	enum TestErrorCode implements BaseErrorCode {
		NOT_FOUND,
		ERROR {
			@Override
			public org.slf4j.event.Level getLogLevel() {
				return org.slf4j.event.Level.ERROR;
			}
		},
		WARNING {
			@Override
			public org.slf4j.event.Level getLogLevel() {
				return org.slf4j.event.Level.WARN;
			}
		};

		@Override
		public HttpStatus getHttpStatus() {
			return HttpStatus.NOT_FOUND;
		}

		@Override
		public String getCode() {
			return switch (this) {
				case NOT_FOUND -> "DOCUMENT-0001";
				case WARNING -> "DOCUMENT-0002";
				case ERROR -> "DOCUMENT-0003";
			};
		}

		@Override
		public String getMessage() {
			return "대상을 찾을 수 없습니다.";
		}
	}
}
