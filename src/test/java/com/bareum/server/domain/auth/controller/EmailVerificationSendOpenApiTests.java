package com.bareum.server.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationSendResponse;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.service.EmailVerificationSendService;
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

class EmailVerificationSendOpenApiTests {

    @Test
    void generatedDocumentationMatchesHttpContractAndScalarLoads() throws Exception {
        // 실제 웹 서버와 OpenAPI 생성기를 사용하되 DB와 메일 발송은 연결하지 않습니다.
        try (ServletWebServerApplicationContext context =
                     (ServletWebServerApplicationContext) SpringApplication.run(
                             DocumentationApplication.class, "--server.port=0");
             HttpClient client = HttpClient.newHttpClient()) {
            String baseUrl = "http://localhost:" + context.getWebServer().getPort();
            JsonMapper mapper = JsonMapper.builder().build();
            HttpResponse<String> apiDocs = get(client, baseUrl + "/v3/api-docs");
            assertThat(apiDocs.statusCode()).isEqualTo(200);

            JsonNode document = mapper.readTree(apiDocs.body());
            JsonNode operation = document.at("/paths/~1api~1v1~1auth~1email-verification/post");
            assertThat(operation.isMissingNode()).isFalse();
            assertThat(operation.at("/requestBody/required").asBoolean()).isTrue();
            assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/EmailVerificationRequest");
            JsonNode requestSchema = document.at("/components/schemas/EmailVerificationRequest");
            assertThat(requestSchema.path("required").toString()).contains("email", "purpose");
            assertThat(requestSchema.at("/properties/purpose/enum").toString())
                    .contains("SIGNUP", "PASSWORD_RESET");

            assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/EmailVerificationSendResponse");
            assertThat(document.at("/components/schemas/EmailVerificationSendResponse/properties").size())
                    .isEqualTo(1);
            assertThat(document.at("/components/schemas/EmailVerificationSendResponse/properties/expiresIn")
                    .isMissingNode()).isFalse();
            assertThat(operation.at("/responses/400/content/application~1problem+json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/BadRequestProblemDetail");
            JsonNode alternatives = document.at("/components/schemas/BadRequestProblemDetail/anyOf");
            assertThat(alternatives.size()).isEqualTo(2);
            assertThat(alternatives.toString()).contains(
                    "#/components/schemas/ProblemDetail",
                    "#/components/schemas/ValidationProblemDetail");
            for (String status : new String[]{"404", "409", "429", "500", "503"}) {
                assertThat(operation.at("/responses/" + status
                        + "/content/application~1problem+json/schema/$ref").asText())
                        .isEqualTo("#/components/schemas/ProblemDetail");
            }

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/email-verification"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"email\":\"user@example.com\",\"purpose\":\"SIGNUP\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree("{\"expiresIn\":298}"));

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
    @Import({OpenApiConfig.class, EmailVerificationSendController.class, GlobalExceptionHandler.class})
    static class DocumentationApplication {

        @Bean
        EmailVerificationSendService emailVerificationSendService() {
            EmailVerificationSendService service = mock(EmailVerificationSendService.class);
            when(service.send(new EmailVerificationRequest("user@example.com", VerificationPurpose.SIGNUP)))
                    .thenReturn(EmailVerificationSendResponse.of(298));
            return service;
        }
    }
}
