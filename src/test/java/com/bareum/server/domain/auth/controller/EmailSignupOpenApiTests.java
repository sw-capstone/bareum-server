package com.bareum.server.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bareum.server.domain.auth.dto.response.EmailSignupResponse;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.EmailSignupService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EmailSignupOpenApiTests {

    @Test
    void documentationMatchesSignupFieldsSuccessAndBothErrorShapes() throws Exception {
        try (var context = (ServletWebServerApplicationContext) SpringApplication.run(
                DocumentationApplication.class, "--server.port=0");
             HttpClient client = HttpClient.newHttpClient()) {
            String base = "http://localhost:" + context.getWebServer().getPort();
            var mapper = JsonMapper.builder().build();
            var docs = client.send(HttpRequest.newBuilder(URI.create(base + "/v3/api-docs")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(docs.statusCode()).isEqualTo(200);
            JsonNode document = mapper.readTree(docs.body());
            var operation = document.at("/paths/~1api~1v1~1auth~1signup/post");
            assertThat(operation.at("/requestBody/required").asBoolean()).isTrue();
            assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/EmailSignupRequest");
            var request = document.at("/components/schemas/EmailSignupRequest");
            assertThat(request.path("required").toString()).contains("email", "name", "password", "verificationToken", "terms");
            assertThat(request.at("/properties/password/minLength").asInt()).isEqualTo(12);
            assertThat(request.at("/properties/password/writeOnly").asBoolean()).isTrue();
            assertThat(request.at("/properties/verificationToken/writeOnly").asBoolean()).isTrue();
            assertThat(request.at("/properties/verificationToken/pattern").asText()).isEqualTo("[A-Za-z0-9_-]{43}");
            assertThat(request.at("/properties/terms/items/$ref").asText()).isEqualTo("#/components/schemas/TermConsentRequest");
            assertThat(document.at("/components/schemas/TermConsentRequest/required").toString()).contains("termId", "version", "agreed");
            assertThat(operation.at("/responses/201/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/EmailSignupResponse");
            assertThat(document.at("/components/schemas/EmailSignupResponse/properties").size()).isEqualTo(1);
            assertThat(operation.at("/responses/400/content/application~1problem+json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/BadRequestProblemDetail");
            assertThat(document.at("/components/schemas/BadRequestProblemDetail/anyOf").size()).isEqualTo(2);
            for (String status : new String[]{"409", "503", "500"}) {
                assertThat(operation.at("/responses/" + status + "/content/application~1problem+json/schema/$ref").asText())
                        .isEqualTo("#/components/schemas/ProblemDetail");
            }
            String body = """
                    {"email":"user@example.invalid","name":"사용자","password":"${EXAMPLE_PASSWORD}",
                     "verificationToken":"%s","terms":[{"termId":1,"version":"1.0","agreed":true}]}
                    """.formatted("A".repeat(43)).replace("${EXAMPLE_PASSWORD}", String.join("", "Password", "123", "!"));
            var success = post(client, base, body);
            assertThat(success.statusCode()).isEqualTo(201);
            assertThat(mapper.readTree(success.body())).isEqualTo(mapper.readTree("{\"memberId\":7}"));
            assertThat(success.headers().firstValue("Set-Cookie")).isEmpty();
            var validation = post(client, base, body.replace("Password123!", "short"));
            assertThat(validation.statusCode()).isEqualTo(400);
            assertThat(mapper.readTree(validation.body()).path("errors").isArray()).isTrue();
            var expired = post(client, base, body.replace("user@example.invalid", "expired@example.invalid"));
            assertThat(expired.statusCode()).isEqualTo(400);
            assertThat(mapper.readTree(expired.body()).path("code").asText()).isEqualTo("AUTH-0013");
            assertThat(mapper.readTree(expired.body()).has("errors")).isFalse();
            var scalar = client.send(HttpRequest.newBuilder(URI.create(base + "/scalar")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(scalar.statusCode()).isEqualTo(200);
            assertThat(scalar.body()).contains("/v3/api-docs", "Bareum API");
        }
    }

    private HttpResponse<String> post(HttpClient client, String base, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/signup"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({OpenApiConfig.class, EmailSignupController.class, GlobalExceptionHandler.class})
    static class DocumentationApplication {
        @Bean
        EmailSignupService emailSignupService() {
            var service = mock(EmailSignupService.class);
            when(service.signup(any())).thenAnswer(invocation -> {
                com.bareum.server.domain.auth.dto.request.EmailSignupRequest request = invocation.getArgument(0);
                if ("expired@example.invalid".equals(request.email())) {
                    throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED);
                }
                return EmailSignupResponse.of(7L);
            });
            return service;
        }
    }
}
