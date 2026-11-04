package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.bareum.server.domain.report.config.ReportUploadProperties;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.source.S3ReportSourceStore;
import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** S3 SDK의 원본 전송, 메타데이터 저장과 버전별 서명 URL 발급을 검증한다. */
class S3ReportSourceStoreTests {
	@Test
	void uploadsExactBytesAndMetadataThenPresignsTheRecordedObjectVersion() throws Exception {
		byte[] bytes = "%PDF-1.7\nwire-fixture".getBytes(StandardCharsets.US_ASCII);
		var pdf = new PdfUploadValidator().prepare(new MockMultipartFile("file", "보고서.pdf", "application/pdf", bytes));
		var received = new java.util.concurrent.atomic.AtomicReference<byte[]>();
		var checksum = new java.util.concurrent.atomic.AtomicReference<String>();
		var filename = new java.util.concurrent.atomic.AtomicReference<String>();
		var encoding = new java.util.concurrent.atomic.AtomicReference<String>();
		var path = new java.util.concurrent.atomic.AtomicReference<String>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			try (exchange) {
				received.set(exchange.getRequestBody().readAllBytes());
				encoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
				checksum.set(exchange.getRequestHeaders().getFirst("x-amz-checksum-sha256"));
				filename.set(exchange.getRequestHeaders().getFirst("x-amz-meta-filename-base64"));
				path.set(exchange.getRequestURI().getPath());
				exchange.getResponseHeaders().set("x-amz-version-id", "wire-version");
				exchange.sendResponseHeaders(200, -1);
			}
		});
		server.start();
		var credentials =
				StaticCredentialsProvider.create(AwsBasicCredentials.create("fixture-access", "fixture-secret"));
		URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		try (var client = S3Client.builder()
				.region(Region.AP_NORTHEAST_2)
				.credentialsProvider(credentials)
				.endpointOverride(endpoint)
				.forcePathStyle(true)
				.httpClientBuilder(UrlConnectionHttpClient.builder())
				.build();
				var presigner =
						S3Presigner.builder().region(Region.AP_NORTHEAST_2).credentialsProvider(credentials).build()) {
			var store = new S3ReportSourceStore(() -> client, () -> presigner, properties(), Clock.systemUTC());
			String key = store.newObjectKey(42);
			assertNotEquals(key, store.newObjectKey(42));
			assertTrue(key.startsWith("reports/42/"));
			var uploaded = store.upload(key, pdf);
			assertEquals("/fixture-bucket/" + key, path.get());
			assertArrayEquals(bytes, decodePayload(received.get(), encoding.get()));
			assertEquals(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(pdf.sha256())), checksum.get());
			assertEquals("보고서.pdf", new String(Base64.getDecoder().decode(filename.get()), StandardCharsets.UTF_8));
			assertEquals("wire-version", uploaded.versionId());
			var download = store.presign(uploaded);
			assertEquals("fixture-bucket.s3.ap-northeast-2.amazonaws.com", download.url().getHost());
			assertTrue(download.url().getQuery().contains("versionId=wire-version"));
			assertTrue(download.url().getQuery().contains("X-Amz-Expires=600"));
			assertTrue(download.expiresAt().isAfter(uploaded.uploadedAt().plusSeconds(590)));
			assertFalse(download.toString().contains("X-Amz-Signature"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void hidesS3FailureDetailsAndDoesNotAutomaticallyRepeatThePut() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			calls.incrementAndGet();
			try (exchange) {
				exchange.getRequestBody().readAllBytes();
				byte[] body = "<Error><Code>InternalError</Code><Message>private-diagnostic</Message></Error>"
						.getBytes(StandardCharsets.US_ASCII);
				exchange.sendResponseHeaders(500, body.length);
				exchange.getResponseBody().write(body);
			}
		});
		server.start();
		try (var client = S3Client.builder()
				.region(Region.AP_NORTHEAST_2)
				.credentialsProvider(StaticCredentialsProvider
						.create(AwsBasicCredentials.create("fixture-access", "fixture-secret")))
				.endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
				.forcePathStyle(true)
				.httpClientBuilder(UrlConnectionHttpClient.builder())
				.overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5))
						.retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
				.build()) {
			var store = new S3ReportSourceStore(() -> client, () -> null, properties(), Clock.systemUTC());
			var pdf = new PdfUploadValidator().prepare(new MockMultipartFile("file", "report.pdf", "application/pdf",
					"%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
			var failure = assertThrows(ReportException.class, () -> store.upload("reports/source.pdf", pdf));
			assertEquals(ReportErrorCode.SOURCE_STORAGE_FAILED, failure.getBaseErrorCode());
			assertNull(failure.getCause());
			assertFalse(failure.getMessage().contains("private-diagnostic"));
			assertEquals(1, calls.get());
		} finally {
			server.stop(0);
		}
	}

	private byte[] decodePayload(byte[] bytes, String encoding) {
		if (encoding == null || !encoding.contains("aws-chunked")) {
			return bytes;
		}
		String wire = new String(bytes, StandardCharsets.ISO_8859_1);
		var decoded = new java.io.ByteArrayOutputStream();
		int position = 0;
		while (true) {
			int lineEnd = wire.indexOf("\r\n", position);
			int size = Integer.parseInt(wire.substring(position, lineEnd).split(";", 2)[0], 16);
			if (size == 0)
				return decoded.toByteArray();
			position = lineEnd + 2;
			decoded.writeBytes(wire.substring(position, position + size).getBytes(StandardCharsets.ISO_8859_1));
			position += size + 2;
		}
	}

	private ReportUploadProperties properties() {
		return new ReportUploadProperties("fixture-bucket", "ap-northeast-2", "reports/",
				"http://127.0.0.1/internal/v1/pdf-parses", "fixture-token", 600, 310, 30);
	}
}
