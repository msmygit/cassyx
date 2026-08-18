package io.cassyx.bulk.impl;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/** Shared Jackson wiring for the JSON / JSONL encoders. */
final class Json {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          // Streaming: the generator, not the mapper, owns the output stream lifecycle.
          .disable(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);

  private Json() {}

  static ObjectMapper mapper() {
    return MAPPER;
  }

  static JsonGenerator generator(OutputStream out) throws IOException {
    return MAPPER.getFactory().createGenerator(out);
  }

  static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
