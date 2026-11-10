package com.bareum.server.domain.report.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import com.bareum.server.domain.report.config.ReportUploadProperties;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.PreparedReportFile;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** S3에 원본과 메타데이터를 저장하고 해당 버전의 다운로드 URL을 발급한다. */
@RequiredArgsConstructor
public final class S3ReportSourceStore implements ReportSourceStore {
	private final Supplier<S3Client> clients;
	private final Supplier<S3Presigner> presigners;
	private final ReportUploadProperties properties;
	private final Clock clock;

	@Override
	public String newObjectKey(long memberId, FileFormat format) {
		return properties.keyPrefix() + memberId + "/" + UUID.randomUUID() + "." + format.extension();
	}

	@Override
	public UploadedReportFile upload(String objectKey, PreparedReportFile file) {
		properties.requireStorageConfigured();
		PutObjectRequest request = PutObjectRequest.builder()
				.bucket(properties.bucket())
				.key(objectKey)
				.contentType(file.format().contentType())
				.contentLength(file.sizeBytes())
				.checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(file.sha256())))
				.metadata(Map.of("sha256", file.sha256(), "size-bytes", Long.toString(file.sizeBytes()),
						"filename-base64",
						Base64.getEncoder().encodeToString(file.filename().getBytes(StandardCharsets.UTF_8))))
				.build();
		try (var input = file.openStream()) {
			var result = clients.get().putObject(request, RequestBody.fromInputStream(input, file.sizeBytes()));
			return new UploadedReportFile(file.format(), objectKey, file.filename(), file.sizeBytes(), file.sha256(),
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

	/** S3 목록의 모든 페이지에서 객체 키가 정확히 일치하는 원본 버전과 삭제 마커를 찾는다. */
	@Override
	public List<String> findVersions(String objectKey) {
		properties.requireStorageConfigured();
		requireOwnedKey(objectKey);
		var versions = new TreeSet<String>();
		try {
			for (var page : clients.get()
					.listObjectVersionsPaginator(ListObjectVersionsRequest.builder()
							.bucket(properties.bucket())
							.prefix(objectKey)
							.build())) {
				page.versions()
						.stream()
						.filter(v -> objectKey.equals(v.key()))
						.forEach(v -> versions.add(v.versionId()));
				page.deleteMarkers()
						.stream()
						.filter(v -> objectKey.equals(v.key()))
						.forEach(v -> versions.add(v.versionId()));
			}
			return List.copyOf(versions);
		} catch (SdkException exception) {
			throw new ReportException(ReportErrorCode.SOURCE_STORAGE_FAILED);
		}
	}

	/** 지정한 S3 버전을 실제로 삭제하며 이미 없는 버전만 성공으로 처리하고 다른 오류는 보존한다. */
	@Override
	public void delete(String objectKey, String versionId) {
		properties.requireStorageConfigured();
		requireOwnedKey(objectKey);
		if (versionId == null || versionId.isBlank()) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
		try {
			clients.get()
					.deleteObject(DeleteObjectRequest.builder()
							.bucket(properties.bucket())
							.key(objectKey)
							.versionId(versionId)
							.build());
		} catch (S3Exception exception) {
			// 버킷 부재나 접근 거부는 삭제 완료로 간주하지 않는다.
			if (exception.statusCode() != 404 || exception.awsErrorDetails() == null
					|| !List.of("NoSuchKey", "NoSuchVersion")
							.contains(exception.awsErrorDetails().errorCode())) {
				throw new ReportException(ReportErrorCode.SOURCE_STORAGE_FAILED);
			}
		} catch (SdkException exception) {
			throw new ReportException(ReportErrorCode.SOURCE_STORAGE_FAILED);
		}
	}

	private void requireOwnedKey(String objectKey) {
		if (objectKey == null || !objectKey.startsWith(properties.keyPrefix())) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
	}
}
