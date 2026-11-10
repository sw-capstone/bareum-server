package com.bareum.server.domain.report.service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.bareum.server.domain.report.body.office.OfficeBodyConverter;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.PreparedReportFile;
import com.bareum.server.domain.report.parsing.office.OfficeReportParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 접수 요청의 검증된 원본으로 로컬 파싱을 수행한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class OfficeReportParsingService {
	private final OfficeBodyConverter converter;
	private final Clock clock;

	public OfficeBodyConverter.Result parse(PreparedReportFile file, Instant deadline) {
		Path temporary = null;
		try {
			temporary = Files.createTempFile("bareum-report-", ".zip");
			try (var stream = file.openStream(); var output = Files.newOutputStream(temporary)) {
				stream.transferTo(output);
			}
			return converter.convert(readDocument(temporary, file, deadline));
		} catch (IOException e) {
			throw new ReportException(ReportErrorCode.FILE_READ_FAILED);
		} finally {
			deleteTemporary(temporary);
		}
	}

	private OfficeReportParser.ParsedReport readDocument(Path temporary, PreparedReportFile file, Instant deadline) {
		try {
			return new OfficeReportParser(() -> !clock.instant().isBefore(deadline)).parse(temporary, file.format());
		} catch (OfficeReportParser.NoReadableTextException e) {
			throw new ReportException(ReportErrorCode.NO_EXTRACTABLE_TEXT);
		} catch (InterruptedIOException e) {
			throw new ReportException(ReportErrorCode.INTAKE_PROCESSING_TIMEOUT);
		} catch (IOException e) {
			throw new ReportException(ReportErrorCode.OFFICE_DOCUMENT_UNREADABLE);
		}
	}

	private static void deleteTemporary(Path temporary) {
		if (temporary != null) {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException e) {
				log.warn("Failed to delete temporary report input: failureType={}", e.getClass().getSimpleName());
			}
		}
	}
}
