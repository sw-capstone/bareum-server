package com.bareum.server.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bareum.server.domain.auth.service.EmailAvailabilityService;
import com.bareum.server.global.config.OpenApiConfig;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;
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
import tools.jackson.databind.json.JsonMapper;

class EmailAvailabilityOpenApiTests {

    @Test
    void badRequestDocumentationCoversMissingParameterAndValidationResponses() throws Exception {
        try (var context = (ServletWebServerApplicationContext) SpringApplication.run(
                DocumentationApplication.class, "--server.port=0");
             var client = HttpClient.newHttpClient()) {
            String baseUrl = "http://localhost:" + context.getWebServer().getPort();
            var mapper = JsonMapper.builder().build();
            var docs = get(client, baseUrl + "/v3/api-docs");
            assertThat(docs.statusCode()).isEqualTo(200);
            var document = mapper.readTree(docs.body());
            var responseSchema = document.at("/paths/~1api~1v1~1auth~1email~1availability/get/responses/400/content/application~1problem+json");
            assertThat(responseSchema.at("/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/BadRequestProblemDetail");
            var alternatives = document.at("/components/schemas/BadRequestProblemDetail/anyOf");
            assertThat(alternatives.size()).isEqualTo(2);
            assertThat(alternatives.toString()).contains(
                    "#/components/schemas/ProblemDetail", "#/components/schemas/ValidationProblemDetail");
            assertThat(document.at("/components/schemas/ValidationProblemDetail/required").toString())
                    .contains("errors");
            assertThat(responseSchema.path("examples").toString()).contains(
                    "MissingRequestParameter", "ValidationFailed");
            assertThat(document.at("/components/examples/MissingRequestParameter/value/code").asText())
                    .isEqualTo("COMMON-0004");

            var missing = get(client, baseUrl + "/api/v1/auth/email/availability");
            assertThat(missing.statusCode()).isEqualTo(400);
            var missingBody = mapper.readTree(missing.body());
            assertThat(missingBody.path("code").asText()).isEqualTo("COMMON-0004");
            assertThat(missingBody.has("errors")).isFalse();

            var invalid = get(client, baseUrl + "/api/v1/auth/email/availability?email=invalid-email");
            assertThat(invalid.statusCode()).isEqualTo(400);
            var invalidBody = mapper.readTree(invalid.body());
            assertThat(invalidBody.path("code").asText()).isEqualTo("COMMON-0002");
            assertThat(invalidBody.path("errors").isArray()).isTrue();
            assertThat(invalidBody.at("/errors/0/field").asText()).isEqualTo("email");
            verifyNoInteractions(context.getBean(EmailAvailabilityService.class));
        }
    }

    private HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({OpenApiConfig.class, EmailAvailabilityController.class, GlobalExceptionHandler.class})
    static class DocumentationApplication {
        @Bean
        EmailAvailabilityService emailAvailabilityService() {
            return mock(EmailAvailabilityService.class);
        }
    }
}
