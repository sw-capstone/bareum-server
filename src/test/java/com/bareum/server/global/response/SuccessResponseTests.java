package com.bareum.server.global.response;

import com.bareum.server.global.exception.handler.GlobalExceptionHandler;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SuccessResponseTests {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void successReturnsDtoWithoutWrapper() throws Exception {
		mockMvc.perform(get("/test/success"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(content().json("{\"id\":1,\"name\":\"example\"}"))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.code").doesNotExist());
	}

	@Test
	void creationPreservesLocationAndDto() throws Exception {
		mockMvc.perform(get("/test/created"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/test/items/1"))
				.andExpect(jsonPath("$.id").value(1));
	}

	@Test
	void noContentHasNoBody() throws Exception {
		mockMvc.perform(get("/test/no-content"))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
	}

	@RestController
	static class TestController {

		@GetMapping("/test/success")
		ResponseEntity<TestResponse> success() {
			return ResponseEntity.ok(new TestResponse(1L, "example"));
		}

		@GetMapping("/test/created")
		ResponseEntity<TestResponse> created() {
			return ResponseEntity.created(URI.create("/test/items/1"))
					.body(new TestResponse(1L, "example"));
		}

		@GetMapping("/test/no-content")
		ResponseEntity<Void> noContent() {
			return ResponseEntity.noContent().build();
		}
	}

	record TestResponse(Long id, String name) {
	}
}
