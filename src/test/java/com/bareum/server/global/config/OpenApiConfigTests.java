package com.bareum.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenApiConfigTests {

	@Test
	void localProfileExposesScalarAndCommonErrorSchemas() throws Exception {
		try (ServletWebServerApplicationContext context = start("local")) {
			HttpResponse<String> response = get(context, "/v3/api-docs");
			assertThat(response.statusCode()).isEqualTo(200);
			JsonNode document = JsonMapper.builder().build().readTree(response.body());
			assertThat(document.at("/info/title").asText()).isEqualTo("Bareum API");
			assertThat(document.at("/servers/0/url").asText()).isEqualTo("/");
			assertThat(document.at("/components/schemas/ProblemDetail/properties/code").isMissingNode()).isFalse();
			assertThat(document.at("/components/schemas/ValidationProblemDetail/properties/errors/items/$ref").asText())
					.isEqualTo("#/components/schemas/ValidationErrorDetail");
			assertThat(document.at("/components/schemas/ValidationErrorDetail/properties/field/type").toString())
					.contains("string", "null");
			assertThat(document.at("/components/examples/ValidationFailed/value/code").asText())
					.isEqualTo("COMMON-0002");
			HttpResponse<String> scalar = get(context, "/scalar");
			assertThat(scalar.statusCode()).isEqualTo(200);
			assertThat(scalar.body()).contains("/v3/api-docs", "Bareum API");
		}
	}

	@Test
	void productionProfileExposesBothDocumentationEndpoints() throws Exception {
		try (ServletWebServerApplicationContext context = start("prod")) {
			assertThat(get(context, "/v3/api-docs").statusCode()).isEqualTo(200);
			assertThat(get(context, "/scalar").statusCode()).isEqualTo(200);
		}
	}

	private ServletWebServerApplicationContext start(String profile) {
		return (ServletWebServerApplicationContext) SpringApplication.run(DocumentationApplication.class,
				"--spring.profiles.active=" + profile, "--server.port=0");
	}

	private HttpResponse<String> get(ServletWebServerApplicationContext context, String path) throws Exception {
		URI uri = URI.create("http://localhost:" + context.getWebServer().getPort() + path);
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
	@Import(OpenApiConfig.class)
	public static class DocumentationApplication {
	}
}
