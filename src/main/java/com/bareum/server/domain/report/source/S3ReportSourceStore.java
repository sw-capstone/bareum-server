package com.bareum.server.domain.report.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.bareum.server.domain.report.config.ReportUploadProperties;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.PreparedPdf;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** S3에 원본과 메타데이터를 저장하고 해당 버전의 다운로드 URL을 발급한다. */
@RequiredArgsConstructor
public final class S3ReportSourceStore implements ReportSourceStore {
	private final Supplier<S3Client> clients;
	private final Supplier<S3Presigner> presigners;
	private final ReportUploadProperties properties;
	private final Clock clock;

	@Override
	public String newObjectKey(long memberId) {
		return properties.keyPrefix() + memberId + "/" + UUID.randomUUID() + ".pdf";
	}

	@Override
	public UploadedReportFile upload(String objectKey, PreparedPdf pdf) {
		properties.requireConfigured();
		PutObjectRequest request = PutObjectRequest.builder()
				.bucket(properties.bucket())
				.key(objectKey)
				.contentType("application/pdf")
				.contentLength(pdf.sizeBytes())
				.checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(pdf.sha256())))
				.metadata(Map.of("sha256", pdf.sha256(), "size-bytes", Long.toString(pdf.sizeBytes()),
						"filename-base64",
						Base64.getEncoder().encodeToString(pdf.filename().getBytes(StandardCharsets.UTF_8))))
				.build();
		try (var input = pdf.openStream()) {
			var result = clients.get().putObject(request, RequestBody.fromInputStream(input, pdf.sizeBytes()));
			return new UploadedReportFile(FileFormat.PDF, objectKey, pdf.filename(), pdf.sizeBytes(), pdf.sha256(),
					result.versionId(), clock.instant());
		} catch (SdkException | IOException exception) {
			throw new ReportException(ReportErrorCode.SOURCE_STORAGE_FAILED);
		}
	}

	@Override
	public DownloadSource presign(UploadedReportFile source) {
		properties.requireConfigured();
		try {
			var signed = presigners.get()
					.presignGetObject(request -> request
							.signatureDuration(Duration.ofSeconds(properties.signedUrlSeconds()))
							.getObjectRequest(get -> get.bucket(properties.bucket())
									.key(source.objectKey())
									.versionId(source.versionId())));
			return new DownloadSource(signed.url().toURI(), signed.expiration());
		} catch (SdkException | java.net.URISyntaxException exception) {
			throw new ReportException(ReportErrorCode.SOURCE_STORAGE_FAILED);
		}
	}
}
