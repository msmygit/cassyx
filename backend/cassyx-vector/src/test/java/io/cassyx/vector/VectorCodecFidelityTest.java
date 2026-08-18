package io.cassyx.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import io.cassyx.vector.api.VectorEncoding;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>CASSANDRA-19333 guard.</b>
 *
 * <p>CASSANDRA-19333 was a silent data-corruption bug in the driver's {@code VectorCodec}: vectors
 * round-tripped through it came back subtly wrong. Nothing about the failure was loud - no
 * exception, no size change, no obvious garbage - which is exactly why this test asserts
 * <b>element-by-element raw-bit equality across a full 1536-dimension vector</b> rather than a size
 * check or an approximate comparison.
 *
 * <p><b>Do not weaken this test.</b> {@code assertThat(decoded).hasSize(1536)} would pass against
 * the corrupt codec. So would comparing magnitudes. Bit equality is the point: {@code -0.0f} and
 * {@code 0.0f} are {@code ==} but are different bit patterns, and a codec that confuses them is
 * corrupting data.
 *
 * <p>This runs in the plain unit suite (no container, no Docker) so the guard is present on every
 * build. {@code VectorRoundTripIT} does the same round trip through a real Cassandra 5.x cluster.
 */
class VectorCodecFidelityTest {

  /** The dimension the plan calls for and the dimension the demo dataset uses (OpenAI-sized). */
  private static final int DIMENSIONS = 1536;

  private final TypeCodec<CqlVector<Float>> codec = vectorCodec();

  @SuppressWarnings("unchecked")
  private static TypeCodec<CqlVector<Float>> vectorCodec() {
    return (TypeCodec<CqlVector<Float>>)
        (TypeCodec<?>) CodecRegistry.DEFAULT.codecFor(DataTypes.vectorOf(DataTypes.FLOAT, DIMENSIONS));
  }

  @Test
  @DisplayName("1536-dimension float vector round-trips with exact bit fidelity (CASSANDRA-19333)")
  void roundTripsFullDimensionVectorExactly() {
    List<Float> original = fixture();
    CqlVector<Float> encoded = CqlVector.newInstance(original);

    ByteBuffer bytes = codec.encode(encoded, ProtocolVersion.DEFAULT);
    assertThat(bytes).isNotNull();
    // 1536 * 4 bytes. A wrong length here is the loud version of the same bug.
    assertThat(bytes.remaining()).isEqualTo(DIMENSIONS * Float.BYTES);

    CqlVector<Float> decoded = codec.decode(bytes, ProtocolVersion.DEFAULT);
    assertThat(decoded).isNotNull();
    assertThat(decoded.size()).isEqualTo(DIMENSIONS);

    for (int i = 0; i < DIMENSIONS; i++) {
      float expected = original.get(i);
      float actual = decoded.get(i);
      assertThat(Float.floatToRawIntBits(actual))
          .as(
              "element %d corrupted: expected %s (bits 0x%08X) but got %s (bits 0x%08X)",
              i,
              expected,
              Float.floatToRawIntBits(expected),
              actual,
              Float.floatToRawIntBits(actual))
          .isEqualTo(Float.floatToRawIntBits(expected));
    }
  }

  @Test
  @DisplayName("Decoding is non-destructive: the same buffer decodes identically twice")
  void decodingDoesNotConsumeTheBuffer() {
    List<Float> original = fixture();
    ByteBuffer bytes = codec.encode(CqlVector.newInstance(original), ProtocolVersion.DEFAULT);

    CqlVector<Float> first = codec.decode(bytes, ProtocolVersion.DEFAULT);
    CqlVector<Float> second = codec.decode(bytes, ProtocolVersion.DEFAULT);

    // A codec that advanced the buffer's position would make the SECOND read of a row's vector
    // return a truncated or empty vector - a corruption that only shows up under paging.
    assertThat(second).isEqualTo(first);
    assertThat(VectorEncoding.toFloatList(second)).isEqualTo(original);
  }

  @Test
  @DisplayName("Our own List<Float> <-> CqlVector conversions preserve every bit too")
  void encodingHelpersPreserveBits() {
    List<Float> original = fixture();

    List<Float> viaDriver = VectorEncoding.toFloatList(VectorEncoding.toCqlVector(original));
    assertThat(viaDriver).isNotNull().hasSize(DIMENSIONS);
    for (int i = 0; i < DIMENSIONS; i++) {
      assertThat(Float.floatToRawIntBits(viaDriver.get(i)))
          .as("element %d", i)
          .isEqualTo(Float.floatToRawIntBits(original.get(i)));
    }

    float[] viaArray = VectorEncoding.toArray(original);
    assertThat(viaArray).hasSize(DIMENSIONS);
    for (int i = 0; i < DIMENSIONS; i++) {
      assertThat(Float.floatToRawIntBits(viaArray[i]))
          .as("element %d", i)
          .isEqualTo(Float.floatToRawIntBits(original.get(i)));
    }
  }

  /**
   * A deterministic 1536-float fixture whose first entries are the values a sloppy codec gets
   * wrong: signed zero, denormals, the extremes of the float range, and exact powers of two.
   */
  static List<Float> fixture() {
    List<Float> values = new ArrayList<>(DIMENSIONS);
    values.add(0.0f);
    values.add(-0.0f);
    values.add(Float.MIN_VALUE);
    values.add(-Float.MIN_VALUE);
    values.add(Float.MIN_NORMAL);
    values.add(Float.MAX_VALUE);
    values.add(-Float.MAX_VALUE);
    values.add(1.0f);
    values.add(-1.0f);
    values.add(Float.intBitsToFloat(0x00000001));

    Random random = new Random(19333L);
    while (values.size() < DIMENSIONS) {
      values.add(random.nextFloat() * 2.0f - 1.0f);
    }
    return List.copyOf(values);
  }
}
