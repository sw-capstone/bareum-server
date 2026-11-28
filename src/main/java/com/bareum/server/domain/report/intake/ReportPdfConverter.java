package com.bareum.server.domain.report.intake;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.stereotype.Component;

import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import lombok.extern.slf4j.Slf4j;

/** 접수한 HWPX와 DOCX를 요청 전용 작업 디렉터리에서 PDF로 변환한다. */
@Slf4j
@Component
public class ReportPdfConverter {
	private static final Duration MAX_CONVERSION_TIME = Duration.ofSeconds(30);
	private static final int MAX_PACKAGE_ENTRIES = 10_000;
	private static final long MAX_EXPANDED_PACKAGE_BYTES = 100_000_000;
	private static final String WRITER_PROFILE = """
			<?xml version="1.0" encoding="UTF-8"?>
			<oor:items xmlns:oor="http://openoffice.org/2001/registry">
			  <item oor:path="/org.openoffice.Office.Common/Security/Scripting">
			    <prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop>
			    <prop oor:name="DisableActiveContent" oor:op="fuse"><value>true</value></prop>
			    <prop oor:name="BlockUntrustedRefererLinks" oor:op="fuse"><value>true</value></prop>
			  </item>
			  <item oor:path="/org.openoffice.Office.Writer/Content/Update">
			    <prop oor:name="Link" oor:op="fuse"><value>2</value></prop>
			  </item>
			</oor:items>
			""";

	public PreparedReportFile convert(PreparedReportFile original, Duration remaining) {
		if (remaining.isNegative() || remaining.isZero()) {
			throw new ReportException(ReportErrorCode.INTAKE_PROCESSING_TIMEOUT);
		}
		long deadline = System.nanoTime() + Math.min(remaining.toNanos(), MAX_CONVERSION_TIME.toNanos());
		Path workspace = createWorkspace();
		try {
			FileFormat format = original.format();
			Path input = workspace.resolve("report" + format.extension());
			Path output = workspace.resolve("report.pdf");
			try (InputStream stream = original.openStream()) {
				Files.copy(stream, input);
			}
			validateOfficePackage(input, format);
			run(command(format, input, output, workspace), workspace, deadline);
			return readPdf(original, output);
		} catch (IOException exception) {
			throw new ReportException(ReportErrorCode.PDF_CONVERSION_UNAVAILABLE);
		} finally {
			deleteWorkspace(workspace);
		}
	}

	private static Path createWorkspace() {
		try {
			return Files.createTempDirectory("bareum-report-pdf-");
		} catch (IOException exception) {
			throw new ReportException(ReportErrorCode.PDF_CONVERSION_UNAVAILABLE);
		}
	}

	private static void validateOfficePackage(Path input, FileFormat format) {
		try (ZipFile archive = new ZipFile(input.toFile())) {
			if (archive.size() > MAX_PACKAGE_ENTRIES) {
				throw new ReportException(ReportErrorCode.PDF_CONVERSION_FAILED);
			}
			long available = MAX_EXPANDED_PACKAGE_BYTES;
			Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				long size = entries.nextElement().getSize();
				if (size < 0 || size > available) {
					throw new ReportException(ReportErrorCode.PDF_CONVERSION_FAILED);
				}
				available -= size;
			}
			boolean matches = switch (format) {
				case HWPX -> hasHwpxMediaType(archive);
				case DOCX -> archive.getEntry("[Content_Types].xml") != null
						&& archive.getEntry("_rels/.rels") != null;
				case PDF -> false;
			};
			if (!matches) {
				throw new ReportException(ReportErrorCode.PDF_CONVERSION_FAILED);
			}
		} catch (IOException exception) {
			throw new ReportException(ReportErrorCode.PDF_CONVERSION_FAILED);
		}
	}

	private static boolean hasHwpxMediaType(ZipFile archive) throws IOException {
		ZipEntry mimeType = archive.getEntry("mimetype");
		if (mimeType == null || archive.getEntry("META-INF/container.xml") == null) {
			return false;
		}
		try (InputStream stream = archive.getInputStream(mimeType)) {
			return new String(stream.readNBytes(64), StandardCharsets.US_ASCII).equals(FileFormat.HWPX.getMediaType());
		}
	}

	private static List<String> command(FileFormat format, Path input, Path output, Path workspace)
			throws IOException {
		if (format == FileFormat.HWPX) {
			return List.of("rhwp", "export-pdf", input.toString(), "-o", output.toString());
		}
		Path profile = workspace.resolve("lo-profile");
		Files.createDirectories(profile.resolve("user"));
		Files.writeString(profile.resolve("user/registrymodifications.xcu"), WRITER_PROFILE);
		return List.of("libreoffice", "-env:UserInstallation=" + profile.toUri(), "--headless", "--norestore",
				"--infilter=Office Open XML Text", "--convert-to", "pdf:writer_pdf_Export", "--outdir",
				workspace.toString(), input.toString());
	}

	private static void run(List<String> command, Path workspace, long deadline) throws IOException {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) {
			throw new ReportException(ReportErrorCode.PDF_CONVERSION_TIMEOUT);
		}
		ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD);
		Map<String, String> environment = builder.environment();
		String path = environment.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
		environment.clear();
		environment.put("PATH", path);
		environment.put("HOME", workspace.toString());
		environment.put("TMPDIR", workspace.toString());
		environment.put("LANG", "C.UTF-8");
		environment.put("RHWP_WEBFONT_OFFLINE", "1");
		Process process = builder.start();
		try {
			process.getOutputStream().close();
			if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
				throw new ReportException(ReportErrorCode.PDF_CONVERSION_TIMEOUT);
			}
			if (process.exitValue() != 0) {
				log.warn("Report PDF converter exited: command={}, exitCode={}", command.getFirst(),
						process.exitValue());
				throw new ReportException(ReportErrorCode.PDF_CONVERSION_FAILED);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ReportException(ReportErrorCode.PDF_CONVERSION_UNAVAILABLE);
		} finally {
			if (process.isAlive()) {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
			}
		}
	}

	private static PreparedReportFile readPdf(PreparedReportFile original, Path output) throws IOException {
		if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
			throw new ReportException(ReportErrorCode.PDF_CONVERSION_FAILED);
		}
		byte[] bytes;
		try (InputStream stream = Files.newInputStream(output, LinkOption.NOFOLLOW_LINKS)) {
			bytes = stream.readNBytes((int)ReportIntakePolicy.MAX_FILE_SIZE_BYTES + 1);
		}
		if (bytes.length > ReportIntakePolicy.MAX_FILE_SIZE_BYTES) {
			throw new ReportException(ReportErrorCode.FILE_TOO_LARGE);
		}
		if (!ReportUploadValidator.hasPdfHeader(bytes)) {
			throw new ReportException(ReportErrorCode.PDF_CONVERSION_FAILED);
		}
		String name = original.filename();
		return new PreparedReportFile(FileFormat.PDF, bytes,
				name.substring(0, name.length() - original.format().extension().length()) + FileFormat.PDF.extension());
	}

	private static void deleteWorkspace(Path workspace) {
		try (Stream<Path> paths = Files.walk(workspace)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException | UncheckedIOException exception) {
			log.warn("Report PDF conversion workspace cleanup failed: failureType={}",
					exception.getClass().getSimpleName());
		}
	}
}
