package io.cassyx.bulk.impl;

import io.cassyx.bulk.api.Encoder;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Excel (.xlsx) encoder (NoSQL Manager parity list, plan section 5.2).
 *
 * <p><b>SXSSF, not XSSF.</b> {@code XSSFWorkbook} holds every row in the heap; {@code SXSSFWorkbook}
 * keeps a sliding window of {@code windowSize} rows in memory and flushes the rest to a temp file,
 * which is the only way this format participates in a streaming unload at all.
 *
 * <p>The XLSX format itself caps a sheet at 1,048,576 rows. Rather than silently truncating - the
 * failure mode this module exists to prevent - the encoder rolls onto a new sheet
 * ({@code data}, {@code data_2}, ...) and keeps going.
 *
 * <p>Options: {@code sheetName} (default {@code data}), {@code windowSize} (default {@code 1000}),
 * {@code header} (default {@code true}).
 */
public final class XlsxEncoder implements Encoder {

  /** Hard limit of the OOXML sheet format, minus the header row. */
  static final int MAX_ROWS_PER_SHEET = 1_048_575;

  @Override
  public String format() {
    return "xlsx";
  }

  @Override
  public String contentType() {
    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  }

  @Override
  public String fileExtension() {
    return "xlsx";
  }

  @Override
  public Writer open(OutputStream out, EncoderContext context) throws IOException {
    return new XlsxWriter(out, context);
  }

  private static final class XlsxWriter implements Writer {

    private final SXSSFWorkbook workbook;
    private final OutputStream out;
    private final EncoderContext context;
    private final String baseSheetName;
    private final boolean header;

    private SXSSFSheet sheet;
    private int sheetIndex = 1;
    private int rowInSheet;

    XlsxWriter(OutputStream out, EncoderContext context) {
      this.out = out;
      this.context = context;
      this.baseSheetName = context.option("sheetName", "data");
      this.header = Boolean.parseBoolean(context.option("header", "true"));
      int windowSize = Integer.parseInt(context.option("windowSize", "1000"));
      this.workbook = new SXSSFWorkbook(windowSize);
      this.workbook.setCompressTempFiles(true);
      startSheet();
    }

    private void startSheet() {
      sheet = workbook.createSheet(sheetIndex == 1 ? baseSheetName : baseSheetName + "_" + sheetIndex);
      rowInSheet = 0;
      if (header) {
        Row headerRow = sheet.createRow(rowInSheet++);
        for (int i = 0; i < context.columns().size(); i++) {
          headerRow.createCell(i).setCellValue(context.columns().get(i));
        }
      }
    }

    @Override
    public void write(Map<String, Object> row) {
      if (rowInSheet > MAX_ROWS_PER_SHEET) {
        sheetIndex++;
        startSheet();
      }
      Row sheetRow = sheet.createRow(rowInSheet++);
      for (int i = 0; i < context.columns().size(); i++) {
        setCell(sheetRow.createCell(i), CellValues.normalise(row.get(context.columns().get(i))));
      }
    }

    /**
     * Excel stores every number as an IEEE-754 double, so a {@code bigint} past 2^53 would come back
     * subtly wrong. Those go in as text instead - a visibly different cell beats a silently altered
     * value.
     */
    private static void setCell(Cell cell, Object value) {
      switch (value) {
        case null -> cell.setBlank();
        case Boolean b -> cell.setCellValue(b);
        case Long l -> {
          if (Math.abs(l) <= (1L << 53)) {
            cell.setCellValue(l.doubleValue());
          } else {
            cell.setCellValue(l.toString());
          }
        }
        case BigDecimal d -> cell.setCellValue(d.toPlainString());
        case Number n -> cell.setCellValue(n.doubleValue());
        case Instant instant -> cell.setCellValue(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        case LocalDate date -> cell.setCellValue(date);
        case LocalDateTime dateTime -> cell.setCellValue(dateTime);
        default -> cell.setCellValue(CellValues.asText(value));
      }
    }

    @Override
    public void close() throws IOException {
      try {
        workbook.write(out);
        out.flush();
      } finally {
        // SXSSF spills to temp files; close() disposes of them.
        workbook.close();
      }
    }
  }
}
