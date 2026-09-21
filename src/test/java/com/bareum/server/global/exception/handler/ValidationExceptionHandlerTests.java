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
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ValidationExceptionHandlerTests {

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
	void requestBodyValidationReturnsFieldMessagesWithoutValuesOrLogs() throws Exception {
		mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"private-invalid-email\",\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.code").value("COMMON-0002"))
				.andExpect(jsonPath("$.detail").value("입력값을 확인해주세요."))
				.andExpect(jsonPath("$.errors.length()").value(2))
				.andExpect(jsonPath("$.errors[*].field", hasItem("email")))
				.andExpect(jsonPath("$.errors[*].field", hasItem("name")))
				.andExpect(jsonPath("$.errors[*].message", hasItem("올바른 이메일 형식을 입력해주세요.")))
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.doesNotContain("private-invalid-email", "rejectedValue"));
		assertThat(appender.list).isEmpty();
	}

	@Test
	void validRequestBodyRemainsSuccessful() throws Exception {
		mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"test@example.com\",\"name\":\"tester\"}"))
				.andExpect(status().isNoContent());
		assertThat(appender.list).isEmpty();
	}

	@Test
	void globalValidationUsesNullField() throws Exception {
		mockMvc.perform(post("/test/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"one\",\"confirmation\":\"two\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-0002"))
				.andExpect(jsonPath("$.errors[0].field").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].message").value("비밀번호 확인 값이 일치하지 않습니다."));
		assertThat(appender.list).isEmpty();
	}

	@Test
	void queryConstraintUsesExternalParameterName() throws Exception {
		mockMvc.perform(get("/test/query-validation").param("page_size", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON-0002"))
				.andExpect(jsonPath("$.errors[0].field").value("page_size"))
				.andExpect(jsonPath("$.errors[0].message").value("크기는 1 이상이어야 합니다."));
		assertThat(appender.list).isEmpty();
	}

	@Test
	void pathConstraintUsesExternalParameterName() throws Exception {
		mockMvc.perform(get("/test/path-validation/0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("documentId"));
	}

	@RestController
	static class TestController {

		@InitBinder
		void initBinder(WebDataBinder binder) {
			if (binder.getTarget() instanceof PairRequest) {
				binder.addValidators(new Validator() {
					@Override
					public boolean supports(Class<?> type) {
						return PairRequest.class == type;
					}

					@Override
					public void validate(Object target, Errors errors) {
						PairRequest pair = (PairRequest) target;
						if (!pair.password().equals(pair.confirmation())) {
							errors.reject("passwordMismatch", "비밀번호 확인 값이 일치하지 않습니다.");
						}
					}
				});
			}
		}

		@PostMapping("/test/validate")
		ResponseEntity<Void> validate(@Valid @RequestBody TestRequest request) {
			return ResponseEntity.noContent().build();
		}

		@PostMapping("/test/pair")
		ResponseEntity<Void> pair(@Valid @RequestBody PairRequest request) {
			return ResponseEntity.noContent().build();
		}

		@GetMapping("/test/query-validation")
		ResponseEntity<Void> queryValidation(
				@RequestParam("page_size") @Min(value = 1, message = "크기는 1 이상이어야 합니다.") int size) {
			return ResponseEntity.noContent().build();
		}

		@GetMapping("/test/path-validation/{documentId}")
		ResponseEntity<Void> pathValidation(@PathVariable("documentId") @Min(1) long id) {
			return ResponseEntity.noContent().build();
		}
	}

	record TestRequest(
			@Email(message = "올바른 이메일 형식을 입력해주세요.") String email,
			@NotBlank(message = "이름을 입력해주세요.") String name) {
	}

	record PairRequest(String password, String confirmation) {
	}
}
