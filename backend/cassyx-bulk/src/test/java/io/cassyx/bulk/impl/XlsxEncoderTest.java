package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Encoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * The XLSX encoder writes through SXSSF, whose output is only meaningful once a real reader opens
 * it. Every assertion therefore re-reads the produced bytes with {@link XSSFWorkbook} - a test that
 * only checked the byte count would pass on a corrupt workbook.
 */
class XlsxEncoderTest {

  private static final List<String> COLUMNS =
      List.of("id", "name", "flag", "amount", "big", "note");

  @Test
  void writesAHeaderRowAndTypedCells() throws IOException {
    try (XSSFWorkbook workbook = read(encode(Map.of(), rows()))) {
      Sheet sheet = workbook.getSheetAt(0);
      assertThat(sheet.getSheetName()).isEqualTo("data");

      Row header = sheet.getRow(0);
      assertThat(header.getCell(0).getStringCellValue()).isEqualTo("id");
      assertThat(header.getCell(5).getStringCellValue()).isEqualTo("note");

      Row row = sheet.getRow(1);
      assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(1.0d);
      assertThat(row.getCell(1).getStringCellValue()).isEqualTo("ada");
      assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.BOOLEAN);
      assertThat(row.getCell(2).getBooleanCellValue()).isTrue();
      assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(2.5d);
    }
  }

  /** A blank cell, not an empty string: Excel users filter on "is blank". */
  @Test
  void nullsBecomeBlankCells() throws IOException {
    try (XSSFWorkbook workbook = read(encode(Map.of(), rows()))) {
      Cell note = workbook.getSheetAt(0).getRow(1).getCell(5);
      assertThat(note.getCellType()).isEqualTo(CellType.BLANK);
    }
  }

  /**
   * Excel stores every number as an IEEE-754 double, so a bigint past 2^53 would silently come back
   * with a different value. The encoder deliberately writes those as text: a visibly different cell
   * beats a quietly wrong one. This is the single most important behaviour in this encoder.
   */
  @Test
  void bigintsBeyondDoublePrecisionAreStoredAsText() throws IOException {
    long huge = (1L << 53) + 3L;
    Map<String, Object> row = row(1, "ada", true, 2.5d, huge, null);

    try (XSSFWorkbook workbook = read(encode(Map.of(), List.of(row)))) {
      Cell big = workbook.getSheetAt(0).getRow(1).getCell(4);
      assertThat(big.getCellType()).isEqualTo(CellType.STRING);
      assertThat(big.getStringCellValue()).isEqualTo(Long.toString(huge));
    }
  }

  /** Exactly 2^53 is still exactly representable, so it stays a real number. */
  @Test
  void bigintsWithinDoublePrecisionStayNumeric() throws IOException {
    long safe = 1L << 53;
    Map<String, Object> row = row(1, "ada", false, 2.5d, safe, "n");

    try (XSSFWorkbook workbook = read(encode(Map.of(), List.of(row)))) {
      Cell big = workbook.getSheetAt(0).getRow(1).getCell(4);
      assertThat(big.getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(big.getNumericCellValue()).isEqualTo((double) safe);
    }
  }

  /** BigDecimal goes in as its plain string so trailing scale is not lost to a double. */
  @Test
  void bigDecimalsAreStoredAsPlainText() throws IOException {
    Map<String, Object> row = row(1, "ada", false, new BigDecimal("1.2300"), 7L, "n");

    try (XSSFWorkbook workbook = read(encode(Map.of(), List.of(row)))) {
      Cell amount = workbook.getSheetAt(0).getRow(1).getCell(3);
      assertThat(amount.getStringCellValue()).isEqualTo("1.2300");
    }
  }

  /** Collections have no Excel equivalent, so they fall back to the JSON rendering. */
  @Test
  void collectionsFallBackToJsonText() throws IOException {
    Map<String, Object> row = row(1, List.of("a", "b"), false, 1.0d, 7L, "n");

    try (XSSFWorkbook workbook = read(encode(Map.of(), List.of(row)))) {
      assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue())
          .isEqualTo("[\"a\",\"b\"]");
    }
  }

  /** With header=false the first physical row is data, not labels. */
  @Test
  void headerCanBeDisabledAndTheSheetRenamed() throws IOException {
    Map<String, String> options = Map.of("header", "false", "sheetName", "export", "windowSize", "5");

    try (XSSFWorkbook workbook = read(encode(options, rows()))) {
      Sheet sheet = workbook.getSheetAt(0);
      assertThat(sheet.getSheetName()).isEqualTo("export");
      assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("ada");
    }
  }

  private static XSSFWorkbook read(byte[] bytes) throws IOException {
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private static Map<String, Object> row(
      Object id, Object name, Object flag, Object amount, Object big, Object note) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("name", name);
    row.put("flag", flag);
    row.put("amount", amount);
    row.put("big", big);
    row.put("note", note);
    return row;
  }

  private static List<Map<String, Object>> rows() {
    return List.of(row(1, "ada", true, 2.5d, 7L, null), row(2, "grace", false, 3.5d, 8L, "hi"));
  }

  private static byte[] encode(Map<String, String> options, List<Map<String, Object>> rows)
      throws IOException {
    Encoder encoder = BulkFactory.encoder("xlsx");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Encoder.Writer writer = encoder.open(out, Encoder.EncoderContext.of(COLUMNS, options))) {
      for (Map<String, Object> row : rows) {
        writer.write(row);
      }
    }
    return out.toByteArray();
  }
}
