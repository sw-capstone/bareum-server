package com.bareum.server.domain.report.body;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class BodyValidatorTests {
	private final BodyJson codec = new BodyJson();
	private final JsonMapper mapper = JsonMapper.builder().build();

	@ParameterizedTest
	@ValueSource(strings = {"pdf", "direct-input", "partial-table"})
	void existingContractsRoundTripWithoutLosingFields(String name) throws Exception {
		String json = BodyFixtures.contract("1.0.0", name);
		var body = codec.read(json);
		assertTrue(mapper.readTree(json)
				.equals((left, right) -> left.isNumber() && right.isNumber()
						? left.decimalValue().compareTo(right.decimalValue())
						: left.equals(right) ? 0 : 1, codec.tree(body)));
	}

	private void rejects(Consumer<ObjectNode> mutate) throws Exception {
		ObjectNode content = (ObjectNode)mapper.readTree(BodyFixtures.contract("1.0.0", "pdf"));
		mutate.accept(content);
		var error = assertThrowsExactly(ReportException.class, () -> codec.read(content.toString()));
		assertEquals(ReportErrorCode.INVALID_CONTENT, error.getBaseErrorCode());
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3})
	void rejectsNegativeOrNonIntegerBlockLineCounts(int blockIndex) throws Exception {
		rejects(body -> block(body, blockIndex).put("lineCount", -1));
		rejects(body -> block(body, blockIndex).put("lineCount", 1.5));
		rejects(body -> block(body, blockIndex).put("lineCount", "2"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"pdf", "direct-input", "partial-table"})
	void bodiesWithoutLineCountsRemainReadableAndDoNotInventCounts(String name) throws Exception {
		ObjectNode json = (ObjectNode)mapper.readTree(BodyFixtures.contract("1.0.0", name));
		for (var block : json.get("blocks")) {
			((ObjectNode)block).remove("lineCount");
		}
		var body = codec.read(json.toString());
		for (var block : body.blocks()) {
			assertNull(block.lineCount());
		}
		for (var block : codec.tree(body).get("blocks")) {
			assertFalse(block.has("lineCount"));
		}
	}

	private static ObjectNode block(ObjectNode body, int index) {
		return (ObjectNode)body.get("blocks").get(index);
	}

	private static ObjectNode cell(ObjectNode body, int index) {
		return (ObjectNode)body.get("blocks").get(3).get("cells").get(index);
	}

	@ParameterizedTest
	@ValueSource(strings = {"sentence", "fragment"})
	void rejectsLegacyUnitKindsInBlocksAndCells(String kind) throws Exception {
		rejects(body -> ((ObjectNode)block(body, 1).get("units").get(0)).put("kind", kind));
		rejects(body -> ((ObjectNode)cell(body, 0).get("units").get(0)).put("kind", kind));
	}

	@Test
	void rejectsDuplicateIdsAcrossKinds() throws Exception {
		rejects(body -> cell(body, 0).put("id", block(body, 0).get("id").asString()));
	}

	@Test
	void rejectsMissingParentsAndCyclicParents() throws Exception {
		rejects(body -> block(body, 1).put("parentId", "00000000-0000-4000-8000-000000000099"));
		rejects(body -> block(body, 0).put("parentId", block(body, 1).get("id").asString()));
	}

	@Test
	void rejectsMismatchedTextOverlapsAndUncoveredText() throws Exception {
		rejects(body -> ((ObjectNode)block(body, 1).get("units").get(0)).put("text", "틀린 문장"));
		rejects(body -> ((ObjectNode)block(body, 1).get("units").get(1)).put("start", 0));
		rejects(body -> ((ArrayNode)block(body, 1).get("units")).remove(1));
	}

	@Test
	void rejectsTableOverlapBoundsAndUnaccountedPositions() throws Exception {
		rejects(body -> cell(body, 3).put("colspan", 2));
		rejects(body -> cell(body, 3).put("rowspan", 99));
		rejects(body -> ((ArrayNode)block(body, 3).get("cells")).remove(7));
	}

	@Test
	void rejectsReferencesToWrongKindsAndCyclicSections() throws Exception {
		rejects(body -> block(body, 1).put("sectionId", block(body, 0).get("id").asString()));
		rejects(body -> {
			ObjectNode section = (ObjectNode)body.get("sections").get(0);
			section.put("parentSectionId", section.get("id").asString());
		});
		rejects(body -> {
			ObjectNode relation = (ObjectNode)block(body, 3).get("headerRelations").get("items").get(0);
			relation.put("cellId", block(body, 0).get("id").asString());
		});
	}

	@Test
	void rejectsInvalidSourceCoordinatesAndMissingCellProvenance() throws Exception {
		rejects(body -> {
			ObjectNode source = (ObjectNode)cell(body, 0).get("sourceRefs").get(0);
			source.remove("parserRow");
			source.remove("parserColumn");
		});
		rejects(body -> {
			ObjectNode location = (ObjectNode)block(body, 0).get("sourceRefs").get(0).get("location");
			location.put("page", 99);
		});
		rejects(body -> {
			ArrayNode box = (ArrayNode)block(body, 0).get("sourceRefs").get(0).get("location").get("bbox");
			box.set(2, mapper.getNodeFactory().numberNode(1000));
		});
	}

	@Test
	void rejectsMissingPropertiesUnknownFieldsAndPrimitiveNulls() throws Exception {
		rejects(body -> block(body, 1).remove("parentId"));
		rejects(body -> block(body, 1).put("unexpected", true));
		rejects(body -> cell(body, 0).putNull("row"));
		rejects(body -> cell(body, 0).put("row", 0.5));
		rejects(body -> cell(body, 0).put("row", "0"));
	}

	@Test
	void validationErrorsUseExistingReportBusinessExceptionHandling() {
		var error = assertThrowsExactly(ReportException.class, () -> codec.read("null"));
		var response = new com.bareum.server.global.exception.handler.GlobalExceptionHandler()
				.handleBusinessException(error,
						new org.springframework.mock.web.MockHttpServletRequest("POST", "/reports"));
		assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("REPORT-0002", response.getBody().getProperties().get("code"));
		assertEquals(ReportErrorCode.INVALID_CONTENT.getMessage(), response.getBody().getDetail());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", "null"})
	void rejectsMalformedOrNullContentWithReportErrorCode(String json) {
		var error = assertThrowsExactly(ReportException.class, () -> codec.read(json));
		assertEquals(ReportErrorCode.INVALID_CONTENT, error.getBaseErrorCode());
	}
}
