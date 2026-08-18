package io.cassyx.bulk.impl;

import io.cassyx.bulk.api.Encoder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * XML encoder (NoSQL Manager parity list, plan section 5.2).
 *
 * <p>Uses StAX rather than DOM precisely because DOM would defeat the point: a document tree for a
 * 50M-row export is exactly the buffering this module exists to avoid.
 *
 * <p>Options: {@code rootElement} (default {@code rows}), {@code rowElement} (default {@code row}).
 * Column names are sanitised into legal XML element names.
 */
public final class XmlEncoder implements Encoder {

  private static final XMLOutputFactory FACTORY = createFactory();

  private static XMLOutputFactory createFactory() {
    XMLOutputFactory factory = XMLOutputFactory.newFactory();
    // We never read XML here, but keep the factory locked down anyway.
    factory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, Boolean.FALSE);
    return factory;
  }

  @Override
  public String format() {
    return "xml";
  }

  @Override
  public String contentType() {
    return "application/xml";
  }

  @Override
  public String fileExtension() {
    return "xml";
  }

  @Override
  public Writer open(OutputStream out, EncoderContext context) throws IOException {
    return new XmlWriter(out, context);
  }

  /** Turns an arbitrary CQL identifier into a legal XML element name. */
  public static String elementName(String column) {
    if (column == null || column.isEmpty()) {
      return "column";
    }
    StringBuilder sb = new StringBuilder(column.length());
    for (int i = 0; i < column.length(); i++) {
      char c = column.charAt(i);
      boolean legal =
          Character.isLetterOrDigit(c) || c == '_' || c == '-' || (c == '.' && i > 0);
      sb.append(legal ? c : '_');
    }
    if (!Character.isLetter(sb.charAt(0)) && sb.charAt(0) != '_') {
      sb.insert(0, '_');
    }
    return sb.toString();
  }

  private static final class XmlWriter implements Writer {

    private final XMLStreamWriter xml;
    private final OutputStream out;
    private final EncoderContext context;
    private final String rowElement;

    XmlWriter(OutputStream out, EncoderContext context) throws IOException {
      this.out = out;
      this.context = context;
      this.rowElement = elementName(context.option("rowElement", "row"));
      try {
        this.xml = FACTORY.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
        xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
        xml.writeStartElement(elementName(context.option("rootElement", "rows")));
      } catch (XMLStreamException e) {
        throw new IOException("Could not start the XML document", e);
      }
    }

    @Override
    public void write(Map<String, Object> row) throws IOException {
      try {
        xml.writeStartElement(rowElement);
        for (String column : context.columns()) {
          String value = CellValues.asText(row.get(column));
          xml.writeStartElement(elementName(column));
          if (value == null) {
            xml.writeAttribute("null", "true");
          } else {
            xml.writeCharacters(value);
          }
          xml.writeEndElement();
        }
        xml.writeEndElement();
      } catch (XMLStreamException e) {
        throw new IOException("Could not write an XML row", e);
      }
    }

    @Override
    public void close() throws IOException {
      try {
        xml.writeEndElement();
        xml.writeEndDocument();
        xml.flush();
        xml.close();
      } catch (XMLStreamException e) {
        throw new IOException("Could not finish the XML document", e);
      } finally {
        out.flush();
      }
    }
  }
}
