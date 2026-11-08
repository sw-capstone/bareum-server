package com.bareum.server.domain.report.intake;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.bareum.server.domain.report.enums.PdfParseErrorCode;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 실제 HTTP 어댑터가 요청 ID와 원본 해시를 주고받도록 응답하는 테스트 서버. */
final class UploadParserFixture implements AutoCloseable {
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final HttpServer server;
	private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	final AtomicInteger calls = new AtomicInteger();
	volatile PdfParseErrorCode error;
	volatile int pageCount = 1;
	volatile String quality = "COMPLETE";
	volatile boolean wrongHash;
	volatile boolean hold;
	volatile boolean holdBody;
	volatile int bodyPrefixBytes;
	volatile CountDownLatch headersSent = new CountDownLatch(1);
	volatile CountDownLatch entered = new CountDownLatch(1);
	volatile CountDownLatch release = new CountDownLatch(1);
	volatile ObjectNode received;
	volatile String authorization;

	UploadParserFixture() {
		try {
			String parsed = Files.readString(Path.of("src/test/resources/report/pdf-extraction-1.2.0.json"));
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(executor);
			server.createContext("/internal/v1/pdf-parses", exchange -> {
				calls.incrementAndGet();
				try (exchange) {
					received = (ObjectNode)mapper
							.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
					authorization = exchange.getRequestHeaders().getFirst("Authorization");
					entered.countDown();
					if (hold) {
						try {
							release.await();
						} catch (InterruptedException exception) {
							Thread.currentThread().interrupt();
							return;
						}
					}
					var body = mapper.createObjectNode().set("reportId", received.get("reportId"));
					body.set("parseAttemptId", received.get("parseAttemptId"));
					int status = 200;
					if (error != null) {
						status = error.getHttpStatus();
						body.putObject("error")
								.put("code", error.name())
								.put("action", error.getAction().name())
								.put("message", "private-parser-diagnostic");
					} else {
						body.put("status", "SUCCEEDED")
								.put("startedAt", "2020-01-01T00:00:00Z")
								.put("completedAt", "2020-01-01T00:00:01Z")
								.put("extractionQuality", quality)
								.putNull("error");
						ObjectNode extraction = (ObjectNode)mapper.readTree(parsed);
						((ObjectNode)extraction.get("document")).put("page_count", pageCount)
								.put("sha256",
										wrongHash ? "0".repeat(64) : received.get("source").get("sha256").asString());
						var pages = extraction.putArray("pages");
						for (int i = 1; i <= pageCount; i++)
							pages.addObject().put("page", i).put("width", 595).put("height", 842);
						body.set("parsed", extraction);
					}
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					byte[] output = mapper.writeValueAsBytes(body);
					exchange.sendResponseHeaders(status, output.length);
					if (holdBody) {
						exchange.getResponseBody().write(output, 0, bodyPrefixBytes);
						exchange.getResponseBody().flush();
						headersSent.countDown();
						try {
							release.await();
						} catch (InterruptedException exception) {
							Thread.currentThread().interrupt();
							return;
						}
						exchange.getResponseBody().write(output, bodyPrefixBytes, output.length - bodyPrefixBytes);
					} else {
						exchange.getResponseBody().write(output);
					}
				}
			});
			server.start();
		} catch (java.io.IOException exception) {
			throw new java.io.UncheckedIOException(exception);
		}
	}

	String endpoint() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1/pdf-parses";
	}

	void reset() {
		calls.set(0);
		error = null;
		pageCount = 1;
		quality = "COMPLETE";
		wrongHash = false;
		hold = false;
		holdBody = false;
		bodyPrefixBytes = 0;
		headersSent = new CountDownLatch(1);
		entered = new CountDownLatch(1);
		release = new CountDownLatch(1);
	}

	@Override
	public void close() {
		release.countDown();
		server.stop(0);
		executor.close();
	}
}
