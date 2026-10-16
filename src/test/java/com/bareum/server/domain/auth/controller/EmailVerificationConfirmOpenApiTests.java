package com.bareum.server.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.dto.response.EmailVerificationConfirmResponse;
import com.bareum.server.domain.auth.service.EmailVerificationConfirmService;
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

class EmailVerificationConfirmOpenApiTests {

    @Test
    void generatedSchemaMatchesProofResponseAndValidationErrors() throws Exception {
        try (var context = (ServletWebServerApplicationContext) SpringApplication.run(
                DocumentationApplication.class, "--server.port=0");
             var client = HttpClient.newHttpClient()) {
            String base = "http://localhost:" + context.getWebServer().getPort();
            var mapper = JsonMapper.builder().build();
            var docs = client.send(HttpRequest.newBuilder(URI.create(base + "/v3/api-docs")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(docs.statusCode()).isEqualTo(200);
            var document = mapper.readTree(docs.body());
            var operation = document.at("/paths/~1api~1v1~1auth~1email-verification~1code/post");
            assertThat(operation.at("/requestBody/required").asBoolean()).isTrue();
            assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/EmailVerificationConfirmRequest");
            assertThat(document.at("/components/schemas/EmailVerificationConfirmRequest/required").toString())
                    .contains("email", "purpose", "code");
            assertThat(document.at("/components/schemas/EmailVerificationConfirmRequest/properties/code/pattern").asText())
                    .isEqualTo("[0-9]{6}");
            assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/EmailVerificationConfirmResponse");
            var fields = document.at("/components/schemas/EmailVerificationConfirmResponse/properties");
            assertThat(fields.size()).isEqualTo(2);
            assertThat(fields.has("verificationToken")).isTrue();
            assertThat(fields.has("expiresIn")).isTrue();
            assertThat(operation.at("/responses/400/content/application~1problem+json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/BadRequestProblemDetail");
            for (String status : new String[]{"409", "429", "500", "503"}) {
                assertThat(operation.at("/responses/" + status + "/content/application~1problem+json/schema/$ref").asText())
                        .isEqualTo("#/components/schemas/ProblemDetail");
            }
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/email-verification/code"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"user@example.com\",\"purpose\":\"SIGNUP\",\"code\":\"012345\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(mapper.readTree(response.body())).isEqualTo(
                    mapper.readTree("{\"verificationToken\":\"test-proof\",\"expiresIn\":600}"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({OpenApiConfig.class, EmailVerificationConfirmController.class, GlobalExceptionHandler.class})
    static class DocumentationApplication {
        @Bean
        EmailVerificationConfirmService emailVerificationConfirmService() {
            var service = mock(EmailVerificationConfirmService.class);
            when(service.confirm(any())).thenReturn(new EmailVerificationConfirmResponse("test-proof", 600));
            return service;
        }
    }
}
