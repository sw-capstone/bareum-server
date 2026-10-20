package com.bareum.server.domain.report.body;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.body.ReportBody.TextBlock;
import com.bareum.server.domain.report.body.ReportBody.Unit;
import com.bareum.server.domain.report.entity.Report;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.enums.ContentStatus;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.enums.SourceFormat;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class ReportBodyPersistenceIntegrationTests {
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private JdbcTemplate jdbc;

	private final BodyJson codec = new BodyJson();

	@ParameterizedTest
	@ValueSource(strings = {"pdf", "direct-input", "partial-table"})
	void storesAndReloadsContractBodiesWithoutLosingFields(String name) throws Exception {
		ReportBody expected = codec.read(BodyFixtures.contract("1.0.0", name));
		ReportContent content = ReportContent.create(persistReport(), expected);
		entityManager.persist(content);
		entityManager.flush();
		Long id = content.getId();
		entityManager.clear();

		ReportContent reloaded = entityManager.find(ReportContent.class, id);
		assertNotSame(content, reloaded);
		assertEquals(expected, reloaded.getContent());
		assertDoesNotThrow(() -> new BodyValidator().validate(reloaded.getContent()));
		assertEquals("jsonb", jdbc.queryForObject(
				"SELECT pg_typeof(content)::text FROM report_content WHERE id = ?", String.class, id));
		assertEquals("object", jdbc.queryForObject(
				"SELECT jsonb_typeof(content) FROM report_content WHERE id = ?", String.class, id));
	}

	@ParameterizedTest
	@ValueSource(strings = {"pdf", "direct-input", "partial-table"})
	void readsExistingContractJsonRows(String name) throws Exception {
		String json = BodyFixtures.contract("1.0.0", name);
		Report report = persistReport();
		entityManager.flush();
		Long id = jdbc.queryForObject(
				"INSERT INTO report_content (report_id, content) VALUES (?, CAST(? AS jsonb)) RETURNING id",
				Long.class, report.getId(), json);
		entityManager.clear();

		assertEquals(codec.read(json), entityManager.find(ReportContent.class, id).getContent());
	}

	@Test
	void preservesWhitespaceEmojiAndCodePointRanges() {
		String text = "  교육  완료.\n가😀나\t조사 예정.  ";
		int start = 2;
		int end = text.codePointCount(0, text.length()) - 2;
		ReportBody expected = new ReportBody("1.0.0", "UNICODE_CODE_POINT", SourceFormat.TEXT,
				List.of(), List.of(new TextBlock(UUID.randomUUID(), "para", null, null, text, text,
						List.of(new Unit(UUID.randomUUID(), start, end,
								text.substring(2, text.length() - 2))), List.of(), null)), List.of(), List.of());
		ReportContent content = ReportContent.create(persistReport(), expected);
		entityManager.persist(content);
		entityManager.flush();
		Long id = content.getId();
		entityManager.clear();

		assertEquals(expected, entityManager.find(ReportContent.class, id).getContent());
		assertEquals(text, jdbc.queryForObject(
				"SELECT content -> 'blocks' -> 0 ->> 'text' FROM report_content WHERE id = ?",
				String.class, id));
	}

	@Test
	void replacesDraftWithoutChangingFrozenBaseline() throws Exception {
		ReportBody original = codec.read(BodyFixtures.contract("1.0.0", "direct-input"));
		ReportContent baseline = ReportContent.create(persistReport(), original);
		baseline.freeze();
		entityManager.persist(baseline);
		ReportContent draft = ReportContent.draftFrom(baseline);
		entityManager.persist(draft);
		entityManager.flush();
		Long baselineId = baseline.getId();
		Long draftId = draft.getId();
		entityManager.clear();

		ReportBody replacement = codec.read(BodyFixtures.contract("1.0.0", "partial-table"));
		entityManager.find(ReportContent.class, draftId).replaceContent(replacement);
		entityManager.flush();
		entityManager.clear();

		ReportContent reloadedBaseline = entityManager.find(ReportContent.class, baselineId);
		ReportContent reloadedDraft = entityManager.find(ReportContent.class, draftId);
		assertEquals(original, reloadedBaseline.getContent());
		assertEquals(ContentStatus.FROZEN, reloadedBaseline.getStatus());
		assertEquals(1, reloadedBaseline.getRevision());
		assertEquals(replacement, reloadedDraft.getContent());
		assertEquals(ContentStatus.DRAFT, reloadedDraft.getStatus());
		assertEquals(2, reloadedDraft.getRevision());
		assertEquals(baselineId, reloadedDraft.getBaseContent().getId());
	}

	private Report persistReport() {
		Member member = Member.createLocal("body-" + UUID.randomUUID() + "@example.invalid", "tester", "hash");
		entityManager.persist(member);
		Report report = Report.createFromText(member, ReportType.PLAN, "본문 저장 테스트");
		entityManager.persist(report);
		return report;
	}
}
