package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.CqlVector;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link CellValues} is the single choke point every encoder funnels driver values through, so a
 * regression here silently corrupts every output format at once. The blob and collection cases
 * matter most: a consumed ByteBuffer produces an empty blob in the *next* encoder that touches it.
 */
class CellValuesTest {

  @Test
  void hexIsCqlshStyleAndLowerCase() {
    assertThat(CellValues.toHex(ByteBuffer.wrap(new byte[0]))).isEqualTo("0x");
    assertThat(CellValues.toHex(ByteBuffer.wrap(new byte[] {0x00, 0x0f, (byte) 0xff, 0x10})))
        .isEqualTo("0x000fff10");
    assertThat(CellValues.toHex(ByteBuffer.wrap("hi".getBytes(StandardCharsets.UTF_8))))
        .isEqualTo("0x6869");
  }

  /**
   * The buffer the driver hands back is shared: reading it destructively would empty the column for
   * every later consumer (Parquet writes the real bytes after JSON has rendered the hex).
   */
  @Test
  void hexDoesNotConsumeTheSourceBuffer() {
    ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3});
    int positionBefore = buffer.position();

    assertThat(CellValues.toHex(buffer)).isEqualTo("0x010203");

    assertThat(buffer.position()).isEqualTo(positionBefore);
    assertThat(buffer.remaining()).isEqualTo(3);
    // And it is still readable a second time, with the same result.
    assertThat(CellValues.toHex(buffer)).isEqualTo("0x010203");
  }

  /** Only the part of the buffer between position and limit is a column's value. */
  @Test
  void hexHonoursThePositionAndLimitOfASlicedBuffer() {
    ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
    buffer.position(1).limit(3);
    assertThat(CellValues.toHex(buffer)).isEqualTo("0x0203");
  }

  /** Scalars must pass through untouched - re-boxing them would lose the JSON number type. */
  @Test
  void scalarsPassThroughUnchanged() {
    assertThat(CellValues.normalise(null)).isNull();
    assertThat(CellValues.normalise("text")).isEqualTo("text");
    assertThat(CellValues.normalise(42)).isEqualTo(42);
    assertThat(CellValues.normalise(42L)).isEqualTo(42L);
    assertThat(CellValues.normalise(true)).isEqualTo(true);
    UUID uuid = UUID.randomUUID();
    assertThat(CellValues.normalise(uuid)).isSameAs(uuid);
    Instant now = Instant.parse("2020-01-02T03:04:05Z");
    assertThat(CellValues.normalise(now)).isSameAs(now);
  }

  @Test
  void blobsBecomeHexStrings() {
    assertThat(CellValues.normalise(ByteBuffer.wrap(new byte[] {(byte) 0xab}))).isEqualTo("0xab");
  }

  /** Sets and lists both flatten to a List so the JSON/XML encoders have one shape to walk. */
  @Test
  void collectionsBecomeLists() {
    assertThat(CellValues.normalise(List.of("a", "b"))).isEqualTo(List.of("a", "b"));

    Set<String> set = new LinkedHashSet<>();
    set.add("x");
    set.add("y");
    assertThat(CellValues.normalise(set)).isEqualTo(List.of("x", "y"));

    // Nesting is recursive: a blob inside a list is still hex.
    assertThat(CellValues.normalise(List.of(ByteBuffer.wrap(new byte[] {1}))))
        .isEqualTo(List.of("0x01"));
  }

  /**
   * Map keys become strings because JSON and XML have no other option; the recursion means a map of
   * lists of blobs still comes out fully rendered.
   */
  @Test
  void mapsBecomeStringKeyedMapsRecursively() {
    Map<Object, Object> nested = new LinkedHashMap<>();
    nested.put(1, List.of("a"));
    Map<Object, Object> outer = new LinkedHashMap<>();
    outer.put("k", nested);

    Object normalised = CellValues.normalise(outer);

    assertThat(normalised).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) normalised;
    assertThat(result).containsOnlyKeys("k");

    @SuppressWarnings("unchecked")
    Map<String, Object> inner = (Map<String, Object>) result.get("k");
    // The integer key 1 becomes the string "1": JSON and XML have no other option.
    assertThat(inner).containsEntry("1", List.of("a"));
  }

  /** CqlDuration has no JSON equivalent, so it becomes its canonical CQL literal. */
  @Test
  void durationsBecomeTheirCqlText() {
    CqlDuration duration = CqlDuration.newInstance(1, 2, 3_000_000_000L);
    assertThat(CellValues.normalise(duration)).isEqualTo(duration.toString());
    assertThat(CellValues.asText(duration)).isEqualTo(duration.toString());
  }

  /** A vector is Iterable but not a Collection, so it needs its own branch to avoid falling through. */
  @Test
  void vectorsBecomeListsOfTheirComponents() {
    CqlVector<Float> vector = CqlVector.newInstance(1.0f, 2.0f);
    assertThat(CellValues.normalise(vector)).isEqualTo(List.of(1.0f, 2.0f));
    assertThat(CellValues.asText(vector)).isEqualTo("[1.0,2.0]");
  }

  @Test
  void asTextRendersScalarsAndNulls() {
    assertThat(CellValues.asText(null)).isNull();
    assertThat(CellValues.asText("text")).isEqualTo("text");
    assertThat(CellValues.asText(42)).isEqualTo("42");
    assertThat(CellValues.asText(true)).isEqualTo("true");
    assertThat(CellValues.asText(ByteBuffer.wrap(new byte[] {0x0a}))).isEqualTo("0x0a");
  }

  /** Structural values become JSON rather than Java's toString, which is not machine-readable. */
  @Test
  void asTextRendersCollectionsAsJson() {
    assertThat(CellValues.asText(List.of("a", "b"))).isEqualTo("[\"a\",\"b\"]");
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("a", 1);
    map.put("b", List.of("c"));
    assertThat(CellValues.asText(map)).isEqualTo("{\"a\":1,\"b\":[\"c\"]}");
  }
}
