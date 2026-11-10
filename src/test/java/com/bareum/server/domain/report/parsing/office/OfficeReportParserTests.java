package com.bareum.server.domain.report.parsing.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.parsing.office.OfficeReportParser.Paragraph;
import com.bareum.server.domain.report.parsing.office.OfficeReportParser.Table;

class OfficeReportParserTests {
	private static final String DOCX_CONTENT_TYPES = """
			<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
			  <Override PartName="/word/document.xml"
			    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
			</Types>
			""";
	private static final String HWPX_MANIFEST = """
			<opf:package xmlns:opf="http://www.idpf.org/2007/opf/">
			  <opf:manifest>
			    <opf:item id="section0" href="Contents/section0.xml"/>
			  </opf:manifest>
			  <opf:spine><opf:itemref idref="section0"/></opf:spine>
			</opf:package>
			""";
	@TempDir
	Path directory;

	private final OfficeReportParser parser = new OfficeReportParser();

	@Test
	void docxPreservesOrderAndMergedCellText() throws IOException {
		Path document = docx("""
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
				  <w:body>
				    <w:p><w:r><w:t>결과보고</w:t></w:r></w:p>
				    <w:tbl>
				      <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge w:val="restart"/></w:tcPr>
				        <w:p><w:r><w:t>홍보</w:t><w:br/><w:t>(3건)</w:t></w:r></w:p>
				      </w:tc></w:tr>
				      <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge/></w:tcPr><w:p/></w:tc></w:tr>
				    </w:tbl>
				    <w:p><w:r><w:t>후속 계획</w:t></w:r></w:p>
				  </w:body>
				</w:document>
				""");

		var blocks = parser.parse(document, FileFormat.DOCX).blocks();
		assertThat(blocks).hasSize(3);
		assertThat(((Paragraph)blocks.getFirst()).text()).isEqualTo("결과보고");
		Table table = (Table)blocks.get(1);
		assertThat(table.cells()).singleElement().satisfies(cell -> {
			assertThat(cell.rowSpan()).isEqualTo(2);
			assertThat(cell.columnSpan()).isEqualTo(2);
			assertThat(cell.text()).isEqualTo("홍보\n(3건)");
		});
		assertThat(((Paragraph)blocks.getLast()).text()).isEqualTo("후속 계획");
	}

	@Test
	void hwpxReadsTableAtItsPositionInsideParagraph() throws IOException {
		Path document = archive("test.hwpx", Map.of("Contents/content.hpf", HWPX_MANIFEST,
				"Contents/section0.xml", """
						<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"
						        xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
						  <hp:p><hp:run>
						    <hp:t>정비 결과</hp:t>
						    <hp:tbl rowCnt="1" colCnt="2"><hp:tr><hp:tc>
						      <hp:subList><hp:p><hp:run><hp:t>2,294</hp:t></hp:run></hp:p></hp:subList>
						      <hp:cellAddr rowAddr="0" colAddr="0"/>
						      <hp:cellSpan rowSpan="1" colSpan="2"/>
						    </hp:tc></hp:tr></hp:tbl>
						    <hp:t>향후 계획</hp:t>
						  </hp:run></hp:p>
						</hs:sec>
						"""));

		var blocks = parser.parse(document, FileFormat.HWPX).blocks();
		assertThat(blocks).hasSize(3);
		assertThat(((Paragraph)blocks.getFirst()).text()).isEqualTo("정비 결과");
		Table table = (Table)blocks.get(1);
		assertThat(table.cells()).singleElement().satisfies(cell -> {
			assertThat(cell.columnSpan()).isEqualTo(2);
			assertThat(cell.text()).isEqualTo("2,294");
			assertThat(cell.source().part()).isEqualTo("Contents/section0.xml");
		});
		assertThat(((Paragraph)blocks.getLast()).text()).isEqualTo("향후 계획");
	}

	@Test
	void rejectsDocumentTypeDeclarations() throws IOException {
		Path document = docx("""
				<!DOCTYPE w:document [<!ENTITY external SYSTEM "file:///secret">]>
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
				  <w:body><w:p><w:r><w:t>&external;</w:t></w:r></w:p></w:body>
				</w:document>
				""");

		assertThatThrownBy(() -> parser.parse(document, FileFormat.DOCX))
				.isInstanceOf(IOException.class)
				.hasMessage("Office XML DTDs and external entities are not supported");
	}

	@Test
	void rejectsOverlappingHwpxCells() throws IOException {
		String cell = """
				<hp:tc><hp:cellAddr rowAddr="0" colAddr="0"/><hp:cellSpan rowSpan="1" colSpan="1"/></hp:tc>
				""";
		Path document = archive("overlap.hwpx", Map.of("Contents/content.hpf", HWPX_MANIFEST,
				"Contents/section0.xml", """
						<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"
						        xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
						  <hp:tbl rowCnt="1" colCnt="1"><hp:tr>%s%s</hp:tr></hp:tbl>
						</hs:sec>
						""".formatted(cell, cell)));

		assertThatThrownBy(() -> parser.parse(document, FileFormat.HWPX))
				.isInstanceOf(IOException.class)
				.hasMessage("Overlapping office table cells");
	}

	@Test
	void rejectsMacroEnabledDocumentRenamedToDocx() throws IOException {
		Path document = archive("renamed.docx", Map.of(
				"[Content_Types].xml", DOCX_CONTENT_TYPES.replace(
						"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
						"application/vnd.ms-word.document.macroEnabled.main+xml"),
				"word/document.xml", """
						<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
						  <w:body><w:p><w:r><w:t>보고서</w:t></w:r></w:p></w:body>
						</w:document>
						"""));

		assertThatThrownBy(() -> parser.parse(document, FileFormat.DOCX))
				.isInstanceOf(IOException.class)
				.hasMessage("Invalid DOCX content type");
	}

	@Test
	void rejectsExcessiveXmlDepthBeforeExtraction() throws IOException {
		Path document = docx("<node>".repeat(129) + "</node>".repeat(129));

		assertThatThrownBy(() -> parser.parse(document, FileFormat.DOCX))
				.isInstanceOf(IOException.class)
				.hasCauseInstanceOf(javax.xml.stream.XMLStreamException.class);
	}

	@Test
	void docxUsesOneRepresentationAndSkipsDeletedContent() throws IOException {
		Path document = docx("""
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
				            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
				  <w:body><w:p>
				    <w:del><w:r><w:delText>이전 내용</w:delText></w:r></w:del>
				    <mc:AlternateContent>
				      <mc:Choice Requires="w"><w:r><w:t>같은 내용</w:t></w:r></mc:Choice>
				      <mc:Fallback><w:r><w:t>같은 내용</w:t></w:r></mc:Fallback>
				    </mc:AlternateContent>
				  </w:p></w:body>
				</w:document>
				""");

		assertThat(parser.parse(document, FileFormat.DOCX).blocks())
				.singleElement()
				.isInstanceOfSatisfying(Paragraph.class,
						paragraph -> assertThat(paragraph.text()).isEqualTo("같은 내용"));
	}

	@Test
	void cancellationPreservesInterruptStatus() throws IOException {
		Path document = docx("""
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
				  <w:body><w:p><w:r><w:t>정상 보고서</w:t></w:r></w:p></w:body>
				</w:document>
				""");
		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(() -> parser.parse(document, FileFormat.DOCX))
					.isInstanceOf(InterruptedIOException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void parsingChecksTheDeadlineWhileProcessingTheDocument() throws IOException {
		Path document = docx("""
				<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
				  <w:body><w:p><w:r><w:t>정상 보고서</w:t></w:r></w:p></w:body>
				</w:document>
				""");
		var checks = new AtomicInteger();
		var timed = new OfficeReportParser(() -> checks.incrementAndGet() >= 5);

		assertThatThrownBy(() -> timed.parse(document, FileFormat.DOCX))
				.isInstanceOf(InterruptedIOException.class)
				.hasMessage("Office document parsing deadline exceeded");
	}

	private Path docx(String body) throws IOException {
		return archive("test.docx", Map.of("[Content_Types].xml", DOCX_CONTENT_TYPES, "word/document.xml", body));
	}

	private Path archive(String filename, Map<String, String> parts) throws IOException {
		Path path = directory.resolve(filename);
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			for (var part : parts.entrySet()) {
				output.putNextEntry(new ZipEntry(part.getKey()));
				output.write(part.getValue().getBytes(StandardCharsets.UTF_8));
				output.closeEntry();
			}
		}
		return path;
	}
}
