package com.bareum.server.domain.report.intake;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.bareum.server.domain.report.config.ReportUploadProperties;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.source.S3ReportSourceStore;
import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;

/** 실제 S3 SDK 요청으로 버전 목록 페이지 처리와 특정 버전 삭제의 실패 구분을 검증한다. */
class S3SourceCleanupTests {
	/** 버전 목록의 모든 페이지를 조회하고 접두사만 같은 다른 객체는 삭제하지 않는지 검증한다. */
	@Test
	void recoversAllExactKeyVersionsAcrossPagesWithoutDeletingPrefixNeighbours() throws Exception {
		var gets = new AtomicInteger();
		List<String> deleted = new ArrayList<>();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			try (exchange) {
				if (exchange.getRequestMethod().equals("DELETE")) {
					assertEquals("/fixture-bucket/reports/source.pdf", exchange.getRequestURI().getPath());
					deleted.add(exchange.getRequestURI().getQuery());
					exchange.sendResponseHeaders(204, -1);
					return;
				}
				int page = gets.incrementAndGet();
				String query = exchange.getRequestURI().getQuery();
				assertTrue(query.contains("prefix=reports/source.pdf"));
				if (page == 2)
					assertTrue(query.contains("version-id-marker=v1"));
				String body = page == 1
						? """
								<ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
								<IsTruncated>true</IsTruncated><NextKeyMarker>reports/source.pdf</NextKeyMarker><NextVersionIdMarker>v1</NextVersionIdMarker>
								<Version><Key>reports/source.pdf</Key><VersionId>v1</VersionId></Version>
								<Version><Key>reports/source.pdf.neighbour</Key><VersionId>other</VersionId></Version>
								</ListVersionsResult>
								"""
						: """
								<ListVersionsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
								<IsTruncated>false</IsTruncated>
								<DeleteMarker><Key>reports/source.pdf</Key><VersionId>v2</VersionId></DeleteMarker>
								</ListVersionsResult>
								""";
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/xml");
				exchange.sendResponseHeaders(200, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		});
		server.start();
		try (var client = client(server)) {
			var store = store(client);
			assertEquals(List.of("v1", "v2"), store.findVersions("reports/source.pdf"));
			store.delete("reports/source.pdf", "v1");
			store.delete("reports/source.pdf", "v2");
			assertEquals(2, gets.get());
			assertEquals(List.of("versionId=v1", "versionId=v2"), deleted);
			assertThrows(ReportException.class, () -> store.delete("reports/source.pdf", null));
			assertThrows(ReportException.class, () -> store.delete("other/source.pdf", "v1"));
		} finally {
			server.stop(0);
		}
	}

	/** 이미 없는 객체나 버전만 삭제 성공으로 간주하고 접근 거부 등은 실패로 남기는지 검증한다. */
	@ParameterizedTest
	@CsvSource({"NoSuchVersion,404,true", "NoSuchKey,404,true", "NoSuchBucket,404,false", "AccessDenied,403,false",
		"InternalError,500,false"})
	void onlyAnAlreadyAbsentObjectVersionCountsAsSuccessfulDeletion(String code, int status, boolean accepted)
			throws Exception {
		var calls = new AtomicInteger();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			try (exchange) {
				calls.incrementAndGet();
				byte[] body = ("<Error><Code>" + code + "</Code><Message>private-s3-diagnostic</Message></Error>")
						.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(status, body.length);
				exchange.getResponseBody().write(body);
			}
		});
		server.start();
		try (var client = client(server)) {
			var store = store(client);
			if (accepted) {
				assertDoesNotThrow(() -> store.delete("reports/source.pdf", "v1"));
			} else {
				var error = assertThrows(ReportException.class, () -> store.delete("reports/source.pdf", "v1"));
				assertFalse(error.toString().contains("private-s3-diagnostic"));
				assertNull(error.getCause());
			}
			assertEquals(1, calls.get());
		} finally {
			server.stop(0);
		}
	}

	private S3Client client(HttpServer server) {
		return S3Client.builder()
				.region(Region.AP_NORTHEAST_2)
				.credentialsProvider(StaticCredentialsProvider
						.create(AwsBasicCredentials.create("fixture-access", "fixture-secret")))
				.endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
				.forcePathStyle(true)
				.httpClientBuilder(UrlConnectionHttpClient.builder())
				.overrideConfiguration(c -> c.retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
				.build();
	}

	private S3ReportSourceStore store(S3Client client) {
		return new S3ReportSourceStore(() -> client, () -> null,
				new ReportUploadProperties("fixture-bucket", "ap-northeast-2", "reports/",
						"http://127.0.0.1/internal/v1/pdf-parses", "fixture-token", 600, 310, 30, 300),
				Clock.systemUTC());
	}
}
