package com.bareum.server.domain.report.parsing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** HTTP 호출 테스트에 사용할 임시 서버와 PDF API 요청 및 응답 샘플을 준비한다. */
final class PdfApiFixture implements AutoCloseable {

	final JsonMapper mapper = JsonMapper.builder().build();
	final HttpServer server;
	final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	final AtomicInteger calls = new AtomicInteger();
	final CountDownLatch entered = new CountDownLatch(1);
	final CountDownLatch release = new CountDownLatch(1);
	volatile String received;
	volatile String authorization;
	volatile String method;
	String body;
	int status = 200;
	String contentType = "application/json";
	String retryAfter;
	boolean hold;

	PdfApiFixture() throws IOException {
		body = success().toString();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(executor);
		server.createContext("/internal/v1/pdf-parses", exchange -> {
			calls.incrementAndGet();
			received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			authorization = exchange.getRequestHeaders().getFirst("Authorization");
			method = exchange.getRequestMethod();
			entered.countDown();
			try (exchange) {
				if (hold) {
					try {
						release.await();
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				exchange.getResponseHeaders().set("Content-Type", contentType);
				if (retryAfter != null) {
					exchange.getResponseHeaders().set("Retry-After", retryAfter);
				}
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		});
		server.start();
	}

	URI endpoint() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1/pdf-parses");
	}

	static PdfParseRequest request() {
		return new PdfParseRequest(101, 201, new PdfParseRequest.Source("reports/101/original.pdf", "결과보고서.pdf",
				100, "a".repeat(64),
				URI.create("https://example-bucket.s3.amazonaws.com/reports/101/original.pdf?signature=example"),
				OffsetDateTime.parse("2099-01-01T00:00:00Z")));
	}

	ObjectNode success() throws IOException {
		ObjectNode result = mapper.createObjectNode();
		result.put("reportId", 101)
				.put("parseAttemptId", 201)
				.put("status", "SUCCEEDED")
				.put("startedAt", "2026-10-18T12:00:00Z")
				.put("completedAt", "2026-10-18T12:00:01Z")
				.put("extractionQuality", "PARTIAL")
				.putNull("error");
		ObjectNode parsed = (ObjectNode)mapper
				.readTree(Files.readString(Path.of("src/test/resources/report/pdf-extraction-1.2.0.json")));
		((ObjectNode)parsed.get("document")).put("sha256", "a".repeat(64));
		result.set("parsed", parsed);
		return result;
	}

	ObjectNode failure(String code, String action) {
		ObjectNode result = mapper.createObjectNode().put("reportId", 101).put("parseAttemptId", 201);
		result.putObject("error").put("code", code).put("action", action).put("message", "파싱 실패");
		return result;
	}

	@Override
	public void close() {
		release.countDown();
		server.stop(0);
		executor.close();
	}
}
