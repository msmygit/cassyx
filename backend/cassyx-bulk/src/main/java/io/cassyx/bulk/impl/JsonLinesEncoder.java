package io.cassyx.bulk.impl;

import io.cassyx.bulk.api.Encoder;
import java.io.IOException;
import java.io.OutputStream;

/**
 * JSON Lines (a.k.a. NDJSON): one JSON object per line, no enclosing array.
 *
 * <p>The right default for a pipe into another tool - the consumer can start work on line 1 instead
 * of waiting for a closing bracket 50M rows later.
 */
public final class JsonLinesEncoder implements Encoder {

  @Override
  public String format() {
    return "jsonl";
  }

  @Override
  public String contentType() {
    return "application/x-ndjson";
  }

  @Override
  public String fileExtension() {
    return "jsonl";
  }

  @Override
  public Writer open(OutputStream out, EncoderContext context) throws IOException {
    return new JsonEncoder.JsonWriter(out, context, false);
  }
}
