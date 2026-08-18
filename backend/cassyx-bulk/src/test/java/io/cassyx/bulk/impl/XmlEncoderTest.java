package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Encoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The XML encoder writes with StAX, which will happily emit a malformed document if the element
 * start/end calls do not balance. So every assertion here goes through a real DOM parse: if the
 * output cannot be parsed, the test fails before it looks at any value.
 */
class XmlEncoderTest {

  private static final List<String> COLUMNS = List.of("id", "name", "note");

  @Test
  void producesAWellFormedDocumentWithDefaultElementNames()
      throws IOException, ParserConfigurationException, SAXException {
    Document document = parse(encode(Map.of(), rows()));

    Element root = document.getDocumentElement();
    assertThat(root.getNodeName()).isEqualTo("rows");
    NodeList rowNodes = root.getElementsByTagName("row");
    assertThat(rowNodes.getLength()).isEqualTo(2);

    Element first = (Element) rowNodes.item(0);
    assertThat(text(first, "id")).isEqualTo("1");
    assertThat(text(first, "name")).isEqualTo("ada & co <lovelace>");
    assertThat(text(second(root), "id")).isEqualTo("2");
  }

  /** Escaping is the reason we use StAX rather than string concatenation. */
  @Test
  void specialCharactersAreEscapedInTheRawBytes() throws IOException {
    String raw = new String(encode(Map.of(), rows()), StandardCharsets.UTF_8);
    assertThat(raw).contains("&amp;").doesNotContain("ada & co");
  }

  @Test
  void rootAndRowElementsAreConfigurable()
      throws IOException, ParserConfigurationException, SAXException {
    Document document =
        parse(encode(Map.of("rootElement", "export", "rowElement", "record"), rows()));

    assertThat(document.getDocumentElement().getNodeName()).isEqualTo("export");
    assertThat(document.getElementsByTagName("record").getLength()).isEqualTo(2);
  }

  /**
   * A null must be distinguishable from an empty string, otherwise a round trip silently turns
   * {@code null} into {@code ''} - so nulls carry an explicit attribute and no text content.
   */
  @Test
  void nullsAreMarkedWithAnAttributeRatherThanEmptied()
      throws IOException, ParserConfigurationException, SAXException {
    Document document = parse(encode(Map.of(), rows()));
    Element firstRow = (Element) document.getElementsByTagName("row").item(0);
    Element note = (Element) firstRow.getElementsByTagName("note").item(0);

    assertThat(note.getAttribute("null")).isEqualTo("true");
    assertThat(note.getTextContent()).isEmpty();

    // The absent-from-the-map column behaves the same way as an explicit null.
    Element secondRow = (Element) document.getElementsByTagName("row").item(1);
    assertThat(((Element) secondRow.getElementsByTagName("note").item(0)).getAttribute("null"))
        .isEqualTo("true");
  }

  /** Collections are flattened to JSON text so an XML consumer still sees the full value. */
  @Test
  void collectionsBecomeJsonText() throws IOException, ParserConfigurationException, SAXException {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 1);
    row.put("name", List.of("a", "b"));
    row.put("note", "x");

    Document document = parse(encode(Map.of(), List.of(row)));
    Element firstRow = (Element) document.getElementsByTagName("row").item(0);
    assertThat(text(firstRow, "name")).isEqualTo("[\"a\",\"b\"]");
  }

  /**
   * CQL identifiers are far more permissive than XML names (quoted identifiers can contain spaces
   * and punctuation, and may start with a digit). An unsanitised name produces an unparsable
   * document, so this mapping is load-bearing.
   */
  @Test
  void elementNameSanitisesIllegalIdentifiers() {
    assertThat(XmlEncoder.elementName("plain")).isEqualTo("plain");
    assertThat(XmlEncoder.elementName("with_underscore-and.dot"))
        .isEqualTo("with_underscore-and.dot");
    // XML names may not start with a digit.
    assertThat(XmlEncoder.elementName("2fast")).isEqualTo("_2fast");
    assertThat(XmlEncoder.elementName("has spaces")).isEqualTo("has_spaces");
    assertThat(XmlEncoder.elementName("weird!name?")).isEqualTo("weird_name_");
    // A leading dot is illegal even though a dot elsewhere is fine.
    assertThat(XmlEncoder.elementName(".lead")).isEqualTo("_lead");
    assertThat(XmlEncoder.elementName("")).isEqualTo("column");
    assertThat(XmlEncoder.elementName(null)).isEqualTo("column");
  }

  /** A sanitised column name must still yield a document the parser accepts end to end. */
  @Test
  void awkwardColumnNamesStillProduceParsableOutput()
      throws IOException, ParserConfigurationException, SAXException {
    Encoder encoder = BulkFactory.encoder("xml");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<String> columns = List.of("2 weird!", "ok");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("2 weird!", "v");
    row.put("ok", "w");

    try (Encoder.Writer writer = encoder.open(out, Encoder.EncoderContext.of(columns))) {
      writer.write(row);
    }

    Document document = parse(out.toByteArray());
    assertThat(document.getElementsByTagName("_2_weird_").getLength()).isEqualTo(1);
  }

  private static Element second(Element root) {
    return (Element) root.getElementsByTagName("row").item(1);
  }

  private static String text(Element row, String column) {
    return row.getElementsByTagName(column).item(0).getTextContent();
  }

  private static Document parse(byte[] bytes) throws ParserConfigurationException, IOException, SAXException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(new ByteArrayInputStream(bytes));
  }

  private static List<Map<String, Object>> rows() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("id", 1);
    first.put("name", "ada & co <lovelace>");
    first.put("note", null);

    Map<String, Object> second = new LinkedHashMap<>();
    second.put("id", 2);
    second.put("name", "grace");

    return List.of(first, second);
  }

  private static byte[] encode(Map<String, String> options, List<Map<String, Object>> rows)
      throws IOException {
    Encoder encoder = BulkFactory.encoder("xml");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Encoder.Writer writer = encoder.open(out, Encoder.EncoderContext.of(COLUMNS, options))) {
      for (Map<String, Object> row : rows) {
        writer.write(row);
      }
    }
    return out.toByteArray();
  }
}
