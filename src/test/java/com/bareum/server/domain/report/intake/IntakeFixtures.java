package com.bareum.server.domain.report.intake;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.body.BodyJson;
import com.bareum.server.domain.report.body.ValidatedReportBody;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.entity.ReportIntakeRequest;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.source.UploadedReportFile;

/** 접수 테스트에 사용할 회원, 원본 파일, 보고서와 요청 기록을 생성한다. */
final class IntakeFixtures {
	static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
	static final String HASH = "a".repeat(64);

	private IntakeFixtures() {
	}

	static Member member() {
		return Member.createLocal("intake-" + UUID.randomUUID() + "@example.invalid", "tester", "hash");
	}

	static UploadedReportFile source(String key) {
		return new UploadedReportFile(FileFormat.PDF, key, "보고서.pdf", 100, HASH, "version-1", NOW);
	}

	static Report report(Member member) {
		return Report.createFromFile(member, ReportType.PLAN, source("reports/" + UUID.randomUUID() + ".pdf"));
	}

	static ReportIntakeRequest request(Member member) {
		return ReportIntakeRequest.reserve(member, UUID.randomUUID(), HASH, NOW);
	}

	static ReportContent content(Report report) throws Exception {
		String json = Files.readString(Path.of("packages/contracts/report-content/1.0.0/examples/pdf.example.json"));
		return ReportContent.create(report, ValidatedReportBody.from(new BodyJson().read(json)));
	}
}
