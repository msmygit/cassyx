package io.cassyx.vector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.datastax.oss.driver.api.core.data.CqlVector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorEncodingTest {

  @Test
  void convertsEveryDriverRepresentation() {
    List<Float> expected = List.of(0.1f, 0.2f, 0.3f);

    assertThat(VectorEncoding.toFloatList(CqlVector.newInstance(expected))).isEqualTo(expected);
    assertThat(VectorEncoding.toFloatList(new float[] {0.1f, 0.2f, 0.3f})).isEqualTo(expected);
    assertThat(VectorEncoding.toFloatList(expected)).isEqualTo(expected);
    assertThat(VectorEncoding.toFloatList(List.of(1, 2))).containsExactly(1.0f, 2.0f);
    assertThat(VectorEncoding.toFloatList(null)).isNull();
  }

  @Test
  void rejectsThingsThatAreNotVectors() {
    assertThatThrownBy(() -> VectorEncoding.toFloatList("[0.1, 0.2]"))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("Not a vector value");
    assertThatThrownBy(() -> VectorEncoding.toFloatList(Arrays.asList(1f, null)))
        .isInstanceOf(VectorException.class);
    assertThatThrownBy(() -> VectorEncoding.toFloatList(List.of("a")))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("not a number");
  }

  @Test
  void buildsDriverVectorsAndArrays() {
    assertThat(VectorEncoding.toCqlVector(List.of(1f, 2f)))
        .isEqualTo(CqlVector.newInstance(List.of(1f, 2f)));
    assertThat(VectorEncoding.toArray(List.of(1f, 2f))).containsExactly(1f, 2f);

    assertThatThrownBy(() -> VectorEncoding.toCqlVector(null)).isInstanceOf(VectorException.class);
    assertThatThrownBy(() -> VectorEncoding.toCqlVector(List.of()))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("at least one dimension");
    assertThatThrownBy(() -> VectorEncoding.toArray(null)).isInstanceOf(VectorException.class);

    List<Float> withNull = new ArrayList<>();
    withNull.add(null);
    assertThatThrownBy(() -> VectorEncoding.toArray(withNull))
        .isInstanceOf(VectorException.class)
        .hasMessageContaining("index 0");
  }

  @Test
  @DisplayName("Export encodes vectors as JSON arrays (plan §6 Display/Export)")
  void encodesAsJsonArray() {
    assertThat(VectorEncoding.toJsonArray(List.of(0.1f, -0.25f))).isEqualTo("[0.1, -0.25]");
    assertThat(VectorEncoding.toJsonArray(null)).isEqualTo("null");
    assertThat(VectorEncoding.toJsonArray(Arrays.asList(1f, null))).isEqualTo("[1.0, null]");
    assertThat(VectorEncoding.encodeForExport(CqlVector.newInstance(List.of(1f, 2f))))
        .isEqualTo("[1.0, 2.0]");
  }

  @Test
  void computesMagnitude() {
    assertThat(VectorEncoding.magnitude(new float[] {3f, 4f})).isCloseTo(5.0d, within(1e-9));
    assertThatThrownBy(() -> VectorEncoding.magnitude(null)).isInstanceOf(VectorException.class);
  }

  @Test
  @DisplayName("A 1536-dimension vector down-samples to 64 sparkline points, not 1536")
  void downSamplesForTheSparkline() {
    List<Float> vector = new ArrayList<>();
    for (int i = 0; i < 1536; i++) {
      vector.add((float) i);
    }

    List<Float> sparkline = VectorEncoding.sparkline(vector);

    assertThat(sparkline).hasSize(VectorEncoding.SPARKLINE_SAMPLES);
    assertThat(sparkline.get(0)).isEqualTo(12f);
    assertThat(sparkline.get(sparkline.size() - 1)).isEqualTo(1524f);
    assertThat(sparkline).isSorted();
  }

  @Test
  void shortVectorsAreReturnedWhole() {
    assertThat(VectorEncoding.sparkline(List.of(1f, 2f, 3f))).containsExactly(1f, 2f, 3f);
    assertThat(VectorEncoding.sparkline(List.of())).isEmpty();
    assertThat(VectorEncoding.sparkline(null)).isEmpty();
    assertThat(VectorEncoding.sparkline(List.of(1f, 2f, 3f, 4f), 0)).hasSize(1);
    assertThat(VectorEncoding.sparkline(List.of(1f, 2f, 3f, 4f), 2)).hasSize(2);
  }
}
