package io.cassyx.migrate.impl;

import io.cassyx.migrate.api.ImportRequest;
import io.cassyx.migrate.api.ImportSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Reference {@link ImportSource}: header-based CSV, read as a stream. */
public final class CsvImportSource implements ImportSource {

  @Override
  public String id() {
    return "csv";
  }

  @Override
  public String displayName() {
    return "CSV file";
  }

  @Override
  public List<String> columns(ImportRequest request) throws IOException {
    try (BufferedReader reader = newReader(request)) {
      String header = reader.readLine();
      return header == null ? List.of() : parseLine(header, delimiter(request));
    }
  }

  @Override
  public ImportCursor open(ImportRequest request) throws IOException {
    BufferedReader reader = newReader(request);
    char delimiter = delimiter(request);
    String header = reader.readLine();
    List<String> columns = header == null ? List.of() : parseLine(header, delimiter);
    return new CsvCursor(reader, columns, delimiter, request.dryRunRows());
  }

  private static char delimiter(ImportRequest request) {
    return request.options().getOrDefault("delimiter", ",").charAt(0);
  }

  private static BufferedReader newReader(ImportRequest request) throws IOException {
    return Files.newBufferedReader(Path.of(request.location()), StandardCharsets.UTF_8);
  }

  /** Visible for testing: RFC 4180 field splitting with {@code ""} escapes. */
  public static List<String> parseLine(String line, char delimiter) {
    List<String> fields = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            current.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          current.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == delimiter) {
        fields.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    fields.add(current.toString());
    return List.copyOf(fields);
  }

  private static final class CsvCursor implements ImportCursor {

    private final BufferedReader reader;
    private final List<String> columns;
    private final char delimiter;
    private final int limit;

    private String pending;
    private int emitted;

    CsvCursor(BufferedReader reader, List<String> columns, char delimiter, int limit) {
      this.reader = reader;
      this.columns = columns;
      this.delimiter = delimiter;
      this.limit = limit;
    }

    @Override
    public boolean hasNext() {
      if (limit > 0 && emitted >= limit) {
        return false;
      }
      if (pending != null) {
        return true;
      }
      try {
        pending = reader.readLine();
      } catch (IOException e) {
        throw new IllegalStateException("Could not read CSV source", e);
      }
      return pending != null;
    }

    @Override
    public Map<String, Object> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      String line = pending;
      pending = null;
      emitted++;
      List<String> values = parseLine(line, delimiter);
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 0; i < columns.size(); i++) {
        row.put(columns.get(i), i < values.size() ? values.get(i) : null);
      }
      return row;
    }

    @Override
    public void close() throws IOException {
      reader.close();
    }
  }
}
