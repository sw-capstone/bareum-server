package com.bareum.server.domain.report.parsing.office;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.bareum.server.domain.report.enums.FileFormat;

/** Reads ordered text and tables from local office documents. */
public final class OfficeReportParser {
	private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
	private static final String HWP_PARAGRAPH = "http://www.hancom.co.kr/hwpml/2011/paragraph";
	private static final String HWP_SECTION = "http://www.hancom.co.kr/hwpml/2011/section";
	private static final String COMPATIBILITY = "http://schemas.openxmlformats.org/markup-compatibility/2006";
	private static final String WORD_PART = "word/document.xml";
	private static final String CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types";
	private static final String DOCX_TYPE =
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
	private static final String OPF = "http://www.idpf.org/2007/opf/";
	private static final Pattern HWP_SECTION_PART = Pattern.compile("Contents/section[0-9]+\\.xml");
	private static final String ELEMENT_INDEX = "office.elementIndex";
	private static final int MAX_FILE_BYTES = 20_000_000;
	private static final int MAX_XML_BYTES = 32 * 1024 * 1024;
	private static final int MAX_PARTS = 256;
	private static final int MAX_ARCHIVE_ENTRIES = 4096;
	private static final int MAX_XML_NODES = 500_000;
	private static final int MAX_XML_DEPTH = 128;
	private static final int MAX_TABLE_SLOTS = 100_000;

	private final BooleanSupplier expired;

	public OfficeReportParser() {
		this(() -> false);
	}

	public OfficeReportParser(BooleanSupplier expired) {
		this.expired = expired;
	}

	public record ParsedReport(
		FileFormat format,
		List<Block> blocks,
		List<Omission> omissions
	) {
		public ParsedReport {
			blocks = List.copyOf(blocks);
			omissions = List.copyOf(omissions);
		}
	}

	public record Omission(
		Source source,
		String element
	) {
	}

	public sealed interface Block permits Paragraph, Table {
		Source source();
	}

	/** An element's zero-based path among XML element siblings, relative to its package part. */
	public record Source(
		String part,
		String path
	) {
	}

	public record Paragraph(
		Source source,
		String text,
		int start
	) implements Block {
	}

	public record Table(
		Source source,
		int rows,
		int columns,
		List<Cell> cells
	) implements Block {
		public Table {
			cells = List.copyOf(cells);
		}
	}

	public record Cell(
		Source source,
		int row,
		int column,
		int rowSpan,
		int columnSpan,
		List<Block> content
	) {
		public Cell {
			content = List.copyOf(content);
		}

		public String text() {
			return String.join("\n", content.stream()
					.filter(Paragraph.class::isInstance)
					.map(Paragraph.class::cast)
					.map(Paragraph::text)
					.toList());
		}
	}

	public ParsedReport parse(Path path, FileFormat format) throws IOException {
		checkActive();
		if (format != FileFormat.DOCX && format != FileFormat.HWPX) {
			throw new IllegalArgumentException("Expected DOCX or HWPX");
		}
		if (Files.size(path) > MAX_FILE_BYTES) {
			throw new IOException("Office document exceeds the file size limit");
		}
		List<Block> blocks = new ArrayList<>();
		List<Omission> omissions = new ArrayList<>();
		try (ZipFile archive = new ZipFile(path.toFile())) {
			checkArchiveEntries(archive);
			XmlParts parts = new XmlParts(archive);
			if (format == FileFormat.DOCX) {
				readDocx(parts, blocks, omissions);
			} else {
				for (String part : hwpxSections(parts)) {
					Element root = parts.read(part);
					if (!is(root, HWP_SECTION, "sec")) {
						throw new IOException("Invalid HWPX section");
					}
					collect(root, part, HWP_PARAGRAPH, blocks);
					findOmissions(root, part, omissions);
				}
			}
		} catch (SAXException | ParserConfigurationException | IllegalArgumentException | ArithmeticException e) {
			throw new IOException("Invalid " + format + " document", e);
		}
		checkActive();
		if (blocks.stream().noneMatch(OfficeReportParser::hasText)) {
			throw new NoReadableTextException();
		}
		return new ParsedReport(format, blocks, omissions);
	}

	public static final class NoReadableTextException extends IOException {
		private NoReadableTextException() {
			super("Office document has no readable text");
		}
	}

	private void checkActive() throws InterruptedIOException {
		if (Thread.currentThread().isInterrupted()) {
			throw new InterruptedIOException("Office document parsing interrupted");
		}
		if (expired.getAsBoolean()) {
			throw new InterruptedIOException("Office document parsing deadline exceeded");
		}
	}

	private static boolean hasText(Block block) {
		return switch (block) {
			case Paragraph paragraph -> !paragraph.text().isBlank();
			case Table table -> table.cells()
					.stream()
					.flatMap(cell -> cell.content().stream())
					.anyMatch(OfficeReportParser::hasText);
		};
	}

	private static void checkArchiveEntries(ZipFile archive) throws IOException {
		if (archive.size() > MAX_ARCHIVE_ENTRIES) {
			throw new IOException("Office archive has too many entries");
		}
		Set<String> names = new HashSet<>();
		var entries = archive.entries();
		while (entries.hasMoreElements()) {
			if (!names.add(entries.nextElement().getName())) {
				throw new IOException("Office archive has duplicate entries");
			}
		}
	}

	private void readDocx(XmlParts parts, List<Block> blocks, List<Omission> omissions)
			throws IOException, SAXException, ParserConfigurationException {
		Element types = parts.read("[Content_Types].xml");
		if (!is(types, CONTENT_TYPES, "Types") || children(types, CONTENT_TYPES, "Override").stream()
				.noneMatch(element -> ("/" + WORD_PART).equals(element.getAttribute("PartName"))
						&& DOCX_TYPE.equals(element.getAttribute("ContentType")))) {
			throw new IOException("Invalid DOCX content type");
		}
		Element root = parts.read(WORD_PART);
		Element body = child(root, WORD, "body");
		if (!is(root, WORD, "document") || body == null) {
			throw new IOException("Invalid DOCX main document");
		}
		collect(body, WORD_PART, WORD, blocks);
		findOmissions(body, WORD_PART, omissions);
	}

	private static List<String> hwpxSections(XmlParts parts)
			throws IOException, SAXException, ParserConfigurationException {
		Element root = parts.read("Contents/content.hpf");
		Element manifest = child(root, OPF, "manifest");
		Element spine = child(root, OPF, "spine");
		if (!is(root, OPF, "package") || manifest == null || spine == null) {
			throw new IOException("Invalid HWPX manifest");
		}
		Map<String, String> items = new HashMap<>();
		for (Element item : children(manifest, OPF, "item")) {
			String id = item.getAttribute("id");
			if (id.isBlank() || items.putIfAbsent(id, item.getAttribute("href")) != null) {
				throw new IOException("Invalid HWPX manifest item");
			}
		}
		Set<String> seen = new HashSet<>();
		List<String> sections = new ArrayList<>();
		for (Element reference : children(spine, OPF, "itemref")) {
			String part = items.get(reference.getAttribute("idref"));
			if (part == null) {
				throw new IOException("Unknown HWPX spine reference");
			}
			if (HWP_SECTION_PART.matcher(part).matches()) {
				if (!seen.add(part) || sections.size() >= MAX_PARTS) {
					throw new IOException("Invalid HWPX section list");
				}
				sections.add(part);
			}
		}
		if (sections.isEmpty()) {
			throw new IOException("Missing HWPX sections");
		}
		return sections;
	}

	private final class XmlParts {
		private final ZipFile archive;
		private int remainingBytes = MAX_XML_BYTES;
		private int remainingNodes = MAX_XML_NODES;

		private XmlParts(ZipFile archive) {
			this.archive = archive;
		}

		private Element read(String part) throws IOException, SAXException, ParserConfigurationException {
			checkActive();
			var entry = archive.getEntry(part);
			if (entry == null || entry.getSize() < 0 || entry.getSize() > remainingBytes) {
				throw new IOException("Missing office XML part or XML size limit exceeded");
			}
			DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			factory.setAttribute("jdk.xml.maxElementDepth", MAX_XML_DEPTH);
			var builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new DefaultHandler());
			try (InputStream stream = archive.getInputStream(entry)) {
				byte[] data = stream.readNBytes((int)entry.getSize() + 1);
				if (data.length != entry.getSize()) {
					throw new IOException("Invalid office XML part size");
				}
				remainingBytes -= data.length;
				checkXmlComplexity(data);
				Element root = builder.parse(new ByteArrayInputStream(data)).getDocumentElement();
				indexElements(root);
				return root;
			}
		}

		// Check the expanded structure before allocating the DOM.
		private void checkXmlComplexity(byte[] data) throws IOException {
			XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
			factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
			factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
			factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setProperty("jdk.xml.maxElementDepth", MAX_XML_DEPTH);
			try {
				var reader = factory.createXMLStreamReader(new ByteArrayInputStream(data));
				try {
					while (reader.hasNext()) {
						checkActive();
						switch (reader.next()) {
							case XMLStreamConstants.START_ELEMENT ->
								remainingNodes -= 1 + reader.getAttributeCount() + reader.getNamespaceCount();
							case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
								throw new IOException("Office XML DTDs and external entities are not supported");
							default -> remainingNodes--;
						}
						if (remainingNodes < 0) {
							throw new IOException("Office XML node limit exceeded");
						}
					}
				} finally {
					reader.close();
				}
			} catch (XMLStreamException e) {
				throw new IOException("Invalid office XML", e);
			}
		}

		private void indexElements(Element parent) {
			int index = 0;
			for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (node instanceof Element element) {
					element.setUserData(ELEMENT_INDEX, index++, null);
					indexElements(element);
				}
			}
		}
	}

	private void collect(Element container, String part, String namespace, List<Block> blocks)
			throws IOException {
		for (Element element : children(container)) {
			checkActive();
			if (is(element, namespace, "tbl")) {
				addTable(element, part, namespace, blocks);
			} else if (is(element, namespace, "p")) {
				ParagraphText text = new ParagraphText(source(part, element));
				paragraph(element, part, namespace, text, blocks);
				text.flush(blocks);
			} else if (is(element, WORD, "sdt") || is(element, WORD, "sdtContent")
					|| is(element, WORD, "ins") || is(element, WORD, "moveTo")) {
				collect(element, part, namespace, blocks);
			} else if (WORD.equals(namespace) && is(element, COMPATIBILITY, "AlternateContent")) {
				collect(docxFallback(element), part, namespace, blocks);
			}
		}
	}

	private void paragraph(
		Element element,
		String part,
		String namespace,
		ParagraphText text,
		List<Block> blocks
	) throws IOException {
		for (Element child : children(element)) {
			checkActive();
			if (discardedWordContent(child) || omittedElement(child)) {
				continue;
			}
			if (WORD.equals(namespace) && is(child, COMPATIBILITY, "AlternateContent")) {
				paragraph(docxFallback(child), part, namespace, text, blocks);
			} else if (is(child, namespace, "tbl")) {
				text.flush(blocks);
				addTable(child, part, namespace, blocks);
			} else if (!appendText(child, namespace, text.value)) {
				paragraph(child, part, namespace, text, blocks);
			}
		}
	}

	// The parser uses the baseline representation instead of reading both alternatives.
	private static Element docxFallback(Element alternative) throws IOException {
		Element fallback = child(alternative, COMPATIBILITY, "Fallback");
		if (fallback == null) {
			throw new IOException("Unsupported DOCX alternate content without fallback");
		}
		return fallback;
	}

	private static final class ParagraphText {
		private final Source source;
		private final StringBuilder value = new StringBuilder();
		private int offset;

		private ParagraphText(Source source) {
			this.source = source;
		}

		private void flush(List<Block> blocks) {
			String text = value.toString();
			if (!text.isBlank()) {
				blocks.add(new Paragraph(source, text, offset));
			}
			offset += text.codePointCount(0, text.length());
			value.setLength(0);
		}
	}

	private void findOmissions(Element element, String part, List<Omission> omissions) throws IOException {
		checkActive();
		if (discardedWordContent(element)) {
			return;
		}
		if (omittedElement(element)) {
			omissions.add(new Omission(source(part, element), element.getLocalName()));
			return;
		}
		if (is(element, COMPATIBILITY, "AlternateContent")) {
			findOmissions(docxFallback(element), part, omissions);
			return;
		}
		for (Element child : children(element)) {
			findOmissions(child, part, omissions);
		}
	}

	private static boolean discardedWordContent(Element element) {
		return is(element, WORD, "del") || is(element, WORD, "moveFrom");
	}

	private static boolean omittedElement(Element element) {
		return is(element, WORD, "txbxContent") || is(element, WORD, "footnoteReference")
				|| is(element, WORD, "endnoteReference") || is(element, WORD, "altChunk")
				|| is(element, HWP_PARAGRAPH, "drawText")
				|| is(element, HWP_PARAGRAPH, "footNote") || is(element, HWP_PARAGRAPH, "endNote");
	}

	private static boolean appendText(Element element, String namespace, StringBuilder text) {
		if (is(element, namespace, "t")) {
			if (WORD.equals(namespace)) {
				text.append(element.getTextContent());
			} else {
				for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
					if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
						text.append(node.getNodeValue());
					} else if (node instanceof Element control) {
						appendText(control, namespace, text);
					}
				}
			}
			return true;
		}
		if (is(element, namespace, "tab")) {
			text.append('\t');
			return true;
		}
		if (is(element, namespace, "br") || is(element, namespace, "cr") || is(element, namespace, "lineBreak")) {
			text.append('\n');
			return true;
		}
		return false;
	}

	private void addTable(Element table, String part, String namespace, List<Block> blocks) throws IOException {
		if (WORD.equals(namespace)) {
			blocks.add(wordTable(table, part));
			return;
		}
		Element caption = child(table, HWP_PARAGRAPH, "caption");
		Element captionBody = child(caption, HWP_PARAGRAPH, "subList");
		boolean after = caption != null && List.of("BOTTOM", "RIGHT").contains(caption.getAttribute("side"));
		if (!after) {
			collect(captionBody, part, namespace, blocks);
		}
		blocks.add(hwpxTable(table, part));
		if (after) {
			collect(captionBody, part, namespace, blocks);
		}
	}

	private Table wordTable(Element table, String part) throws IOException {
		List<Element> rows = children(table, WORD, "tr");
		Element grid = child(table, WORD, "tblGrid");
		int columns = children(grid, WORD, "gridCol").size();
		List<Cell> cells = new ArrayList<>();
		Map<Integer, Integer> previousMerges = Map.of();
		for (int row = 0; row < rows.size(); row++) {
			Element rowElement = rows.get(row);
			Element rowProperties = child(rowElement, WORD, "trPr");
			int column = wordNumber(rowProperties, "gridBefore", 0);
			int after = wordNumber(rowProperties, "gridAfter", 0);
			if (column < 0 || after < 0) {
				throw new IOException("Invalid DOCX row grid offset");
			}
			Map<Integer, Integer> merges = new HashMap<>();
			for (Element cell : children(rowElement, WORD, "tc")) {
				checkActive();
				Element properties = child(cell, WORD, "tcPr");
				int width = wordNumber(properties, "gridSpan", 1);
				Element merge = child(properties, WORD, "vMerge");
				List<Block> content = cellContent(cell, part, WORD);
				String mergeValue = merge == null ? "" : merge.getAttributeNS(WORD, "val");
				if (width < 1
						|| (merge != null && !List.of("", "restart", "continue").contains(mergeValue))) {
					throw new IOException("Invalid DOCX cell span");
				}
				if (merge != null && !"restart".equals(mergeValue)) {
					Integer index = previousMerges.get(column);
					if (index == null || cells.get(index).columnSpan() != width || !content.isEmpty()) {
						throw new IOException("Invalid DOCX vertical merge continuation");
					}
					Cell anchor = cells.get(index);
					cells.set(index, new Cell(anchor.source(), anchor.row(), column, anchor.rowSpan() + 1, width,
							anchor.content()));
					merges.put(column, index);
				} else {
					if (merge != null) {
						merges.put(column, cells.size());
					}
					cells.add(new Cell(source(part, cell), row, column, 1, width, content));
				}
				column = Math.addExact(column, width);
			}
			columns = Math.max(columns, Math.addExact(column, after));
			previousMerges = merges;
		}
		return checkedTable(source(part, table), rows.size(), columns, cells);
	}

	private Table hwpxTable(Element table, String part) throws IOException {
		List<Cell> cells = new ArrayList<>();
		int rows = Integer.parseInt(table.getAttribute("rowCnt"));
		int columns = Integer.parseInt(table.getAttribute("colCnt"));
		for (Element row : children(table, HWP_PARAGRAPH, "tr")) {
			for (Element cell : children(row, HWP_PARAGRAPH, "tc")) {
				checkActive();
				Element address = child(cell, HWP_PARAGRAPH, "cellAddr");
				Element span = child(cell, HWP_PARAGRAPH, "cellSpan");
				if (address == null || span == null) {
					throw new IOException("Missing HWPX cell address or span");
				}
				int r = Integer.parseInt(address.getAttribute("rowAddr"));
				int c = Integer.parseInt(address.getAttribute("colAddr"));
				int height = Integer.parseInt(span.getAttribute("rowSpan"));
				int width = Integer.parseInt(span.getAttribute("colSpan"));
				if (r < 0 || c < 0 || height < 1 || width < 1 || (long)r + height > rows || (long)c + width > columns) {
					throw new IOException("Invalid HWPX cell span");
				}
				List<Block> content = cellContent(cell, part, HWP_PARAGRAPH);
				cells.add(new Cell(source(part, cell), r, c, height, width, content));
			}
		}
		cells.sort(Comparator.comparingInt(Cell::row).thenComparingInt(Cell::column));
		return checkedTable(source(part, table), rows, columns, cells);
	}

	private static int wordNumber(Element parent, String name, int defaultValue) {
		Element value = child(parent, WORD, name);
		return value == null ? defaultValue : Integer.parseInt(value.getAttributeNS(WORD, "val"));
	}

	private List<Block> cellContent(Element cell, String part, String namespace) throws IOException {
		Element container = WORD.equals(namespace) ? cell : child(cell, namespace, "subList");
		List<Block> blocks = new ArrayList<>();
		collect(container, part, namespace, blocks);
		return blocks;
	}

	private static Table checkedTable(Source source, int rows, int columns, List<Cell> cells) throws IOException {
		if (rows <= 0 || columns <= 0 || (long)rows * columns > MAX_TABLE_SLOTS || cells.isEmpty()) {
			throw new IOException("Invalid or oversized office table");
		}
		BitSet occupied = new BitSet(rows * columns);
		for (Cell cell : cells) {
			for (int row = cell.row(); row < cell.row() + cell.rowSpan(); row++) {
				int start = row * columns + cell.column();
				int end = start + cell.columnSpan();
				int next = occupied.nextSetBit(start);
				if (next >= 0 && next < end) {
					throw new IOException("Overlapping office table cells");
				}
				occupied.set(start, end);
			}
		}
		return new Table(source, rows, columns, cells);
	}

	private static Source source(String part, Element element) {
		StringBuilder path = new StringBuilder();
		for (Node node = element; node.getParentNode() instanceof Element; node = node.getParentNode()) {
			path.insert(0, "/" + node.getUserData(ELEMENT_INDEX));
		}
		return new Source(part, path.toString());
	}

	private static boolean is(Element element, String namespace, String name) {
		return namespace.equals(element.getNamespaceURI()) && name.equals(element.getLocalName());
	}

	private static Element child(Element element, String namespace, String name) {
		return children(element, namespace, name).stream().findFirst().orElse(null);
	}

	private static List<Element> children(Element element, String namespace, String name) {
		return children(element).stream().filter(child -> is(child, namespace, name)).toList();
	}

	private static List<Element> children(Element element) {
		List<Element> result = new ArrayList<>();
		if (element != null) {
			for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (node instanceof Element child) {
					result.add(child);
				}
			}
		}
		return result;
	}
}
