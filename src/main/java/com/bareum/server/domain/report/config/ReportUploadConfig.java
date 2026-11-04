package com.bareum.server.domain.report.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.bareum.server.domain.report.parsing.HttpPdfParser;
import com.bareum.server.domain.report.parsing.PdfParser;
import com.bareum.server.domain.report.source.ReportSourceStore;
import com.bareum.server.domain.report.source.S3ReportSourceStore;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** S3 저장소와 PDF 파싱 서버 통신에 필요한 객체를 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
	ReportUploadProperties.class, ReportIntakeProperties.class
})
public class ReportUploadConfig {
	/** 원본 삭제 지연이 접수 복구와 다른 예약 작업을 막지 않도록 전용 스케줄러를 등록한다. */
	@Bean(defaultCandidate = false)
	@ConditionalOnProperty(name = "report.intake.maintenance-enabled", havingValue = "true", matchIfMissing = true)
	public ThreadPoolTaskScheduler reportIntakeScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(2);
		scheduler.setThreadNamePrefix("report-intake-maintenance-");
		return scheduler;
	}

	@Bean
	@Lazy
	public S3Client reportS3Client(ReportUploadProperties properties) {
		properties.requireConfigured();
		return S3Client.builder()
				.region(Region.of(properties.region()))
				.httpClientBuilder(UrlConnectionHttpClient.builder()
						.connectionTimeout(Duration.ofSeconds(5))
						.socketTimeout(Duration.ofSeconds(properties.storageTimeoutSeconds())))
				.overrideConfiguration(
						config -> config.apiCallTimeout(Duration.ofSeconds(properties.storageTimeoutSeconds()))
								.apiCallAttemptTimeout(Duration.ofSeconds(properties.storageTimeoutSeconds()))
								.retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
				.build();
	}

	@Bean
	@Lazy
	public S3Presigner reportS3Presigner(ReportUploadProperties properties) {
		properties.requireConfigured();
		return S3Presigner.builder().region(Region.of(properties.region())).build();
	}

	@Bean
	public ReportSourceStore reportSourceStore(
		ObjectProvider<S3Client> clients,
		ObjectProvider<S3Presigner> presigners,
		ReportUploadProperties properties,
		Clock clock
	) {
		return new S3ReportSourceStore(clients::getObject, presigners::getObject, properties, clock);
	}

	@Bean
	public HttpClient reportPdfHttpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	@Bean
	public PdfParser pdfParser(@Qualifier("reportPdfHttpClient") HttpClient client, ReportUploadProperties properties) {
		return (request, remaining) -> {
			properties.requireConfigured();
			return new HttpPdfParser(client, URI.create(properties.parserEndpoint()), properties.parserToken(),
					Duration.ofSeconds(properties.parserTimeoutSeconds())).parse(request, remaining);
		};
	}
}
