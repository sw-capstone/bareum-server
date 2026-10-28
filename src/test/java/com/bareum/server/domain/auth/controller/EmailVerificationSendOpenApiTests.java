package com.bareum.server.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.service.EmailVerificationSendService;
import com.bareum.server.global.config.OpenApiConfig;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EmailVerificationSendOpenApiTests {

	@Test
	void generatedDocumentationMatchesHttpContractAndScalarLoads() throws Exception {
		// 실제 웹 서버와 OpenAPI 생성기를 사용하되 DB와 메일 발송은 연결하지 않습니다.
		try (ServletWebServerApplicationContext context =
				(ServletWebServerApplicationContext)SpringApplication
						.run(
								DocumentationApplication.class, "--server.port=0");
				HttpClient client = HttpClient.newHttpClient()) {
			String baseUrl = "http://localhost:" + context.getWebServer().getPort();
			JsonMapper mapper = JsonMapper.builder()
					.build();
			HttpResponse<String> apiDocs = get(client, baseUrl + "/v3/api-docs");
			assertThat(apiDocs.statusCode()).isEqualTo(200);

			JsonNode document = mapper.readTree(apiDocs.body());
			for (String operationPath : new String[] {
				"/paths/~1api~1v1~1auth~1email-verification/post",
				"/paths/~1api~1v1~1auth~1email-verification~1deliveries~1{requestId}/get",
				"/paths/~1api~1v1~1auth~1email-verification~1code/post",
				"/paths/~1api~1v1~1auth~1email-verification~1resend/post"
			}) {
				assertThat(document.at(operationPath).isMissingNode()).isFalse();
			}

			JsonNode operation = document.at("/paths/~1api~1v1~1auth~1email-verification/post");
			assertThat(operation.isMissingNode()).isFalse();
			assertThat(operation.at("/requestBody/required").asBoolean()).isTrue();
			assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText())
					.isEqualTo("#/components/schemas/EmailVerificationRequest");
			JsonNode requestSchema = document.at("/components/schemas/EmailVerificationRequest");
			assertThat(requestSchema.path("required").toString()).contains("email", "purpose");
			assertThat(requestSchema.at("/properties/purpose/enum").toString())
					.contains("SIGNUP", "PASSWORD_RESET");

			assertThat(operation.at("/responses/202/content/application~1json/schema/$ref").asText())
					.isEqualTo("#/components/schemas/EmailVerificationDeliveryResponse");
			assertThat(document.at("/components/schemas/EmailVerificationDeliveryResponse/properties").size())
					.isEqualTo(3);
			assertThat(document.at("/components/schemas/EmailVerificationDeliveryResponse/properties/expiresIn")
					.isMissingNode()).isFalse();
			assertThat(operation.at("/responses/400/content/application~1problem+json/schema/$ref").asText())
					.isEqualTo("#/components/schemas/BadRequestProblemDetail");
			JsonNode alternatives = document.at("/components/schemas/BadRequestProblemDetail/anyOf");
			assertThat(alternatives.size()).isEqualTo(2);
			assertThat(alternatives.toString()).contains(
					"#/components/schemas/ProblemDetail",
					"#/components/schemas/ValidationProblemDetail");
			for (String status : new String[] {"404", "409", "429", "500", "503"}) {
				assertThat(operation.at("/responses/" + status
						+ "/content/application~1problem+json/schema/$ref")
						.asText())
						.isEqualTo("#/components/schemas/ProblemDetail");
			}

			HttpResponse<String> response = client
					.send(
							HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/email-verification"))
									.header("Content-Type", "application/json")
									.POST(HttpRequest.BodyPublishers
											.ofString(
													"{\"email\":\"user@example.com\",\"purpose\":\"SIGNUP\"}"))
									.build(),
							HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(202);
			assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
			assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree(
					"{\"requestId\":\"00000000-0000-4000-8000-000000000001\",\"status\":\"PENDING\",\"expiresIn\":0}"));

			JsonNode statusOperation = document
					.at("/paths/~1api~1v1~1auth~1email-verification~1deliveries~1{requestId}/get");
			assertThat(statusOperation.at("/responses/200/content/application~1json/schema/$ref").asText())
					.isEqualTo("#/components/schemas/EmailVerificationDeliveryResponse");
			assertThat(statusOperation.at("/responses/404/content").isMissingNode()).isTrue();
			var sent = get(client, baseUrl + response.headers().firstValue("Location").orElseThrow());
			assertThat(sent.statusCode()).isEqualTo(200);
			assertThat(sent.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
			assertThat(mapper.readTree(sent.body()).path("status").asText()).isEqualTo("SENT");
			assertThat(mapper.readTree(sent.body()).path("expiresIn").asLong()).isEqualTo(300);

			HttpResponse<String> scalar = get(client, baseUrl + "/scalar");
			assertThat(scalar.statusCode()).isEqualTo(200);
			assertThat(scalar.body()).contains("/v3/api-docs", "Bareum API");
		}
	}

	private HttpResponse<String> get(HttpClient client, String url) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
	@Import({OpenApiConfig.class, EmailVerificationController.class,
		GlobalExceptionHandler.class, com.bareum.server.global.config.DocumentationSecurityConfig.class})
	static class DocumentationApplication {
		@Bean
		com.bareum.server.domain.auth.service.EmailVerificationConfirmService confirmService() {
			return mock(com.bareum.server.domain.auth.service.EmailVerificationConfirmService.class);
		}

		@Bean
		com.bareum.server.domain.auth.service.EmailVerificationResendService resendService() {
			return mock(com.bareum.server.domain.auth.service.EmailVerificationResendService.class);
		}

		@Bean
		com.bareum.server.domain.auth.service.EmailVerificationDeliveryService deliveryService() {
			var service = mock(com.bareum.server.domain.auth.service.EmailVerificationDeliveryService.class);
			var id = java.util.UUID.fromString("00000000-0000-4000-8000-000000000001");
			when(service.status(id))
					.thenReturn(java.util.Optional.of(new EmailVerificationDeliveryResponse(id, "SENT", 300)));
			return service;
		}

		@Bean
		EmailVerificationSendService emailVerificationSendService() {
			EmailVerificationSendService service = mock(EmailVerificationSendService.class);
			when(service.send(new EmailVerificationRequest("user@example.com", VerificationPurpose.SIGNUP)))
					.thenReturn(EmailVerificationDeliveryResponse
							.pending(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001")));
			return service;
		}
	}
}
