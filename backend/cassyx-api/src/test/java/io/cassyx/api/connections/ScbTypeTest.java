package io.cassyx.api.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.connections.dto.AstraBundleDownloadRequest;
import io.cassyx.api.connections.dto.ScbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two-valued {@code ScbType} of plan section 3.1, deviation 1 - and the message a caller gets
 * for the phantom third value.
 */
class ScbTypeTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void hasExactlyTwoValues() {
    assertThat(ScbType.values()).containsExactly(ScbType.DEFAULT, ScbType.CUSTOM);
  }

  @Test
  void serialisesWithTheContractsLowercaseNames() throws Exception {
    assertThat(json.writeValueAsString(ScbType.DEFAULT)).isEqualTo("\"default\"");
    assertThat(json.writeValueAsString(ScbType.CUSTOM)).isEqualTo("\"custom\"");
  }

  @Test
  @DisplayName("an absent scbType defaults to 'default' rather than NPE-ing (deviation 2)")
  void absentMeansDefault() {
    assertThat(ScbType.parse(null)).isEqualTo(ScbType.DEFAULT);
    assertThat(ScbType.parse("")).isEqualTo(ScbType.DEFAULT);
    assertThat(ScbType.parse("  ")).isEqualTo(ScbType.DEFAULT);
    assertThat(ScbType.parse("DEFAULT")).isEqualTo(ScbType.DEFAULT);
    assertThat(ScbType.parse(" Custom ")).isEqualTo(ScbType.CUSTOM);
  }

  @Test
  @DisplayName("'region' is rejected and the message points at the separate region field")
  void rejectsThePhantomRegionType() {
    assertThatThrownBy(() -> ScbType.parse("region"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("\"default\", \"custom\"")
        .hasMessageContaining("separate \"region\" field");
  }

  @Test
  void rejectsAnythingElse() {
    assertThatThrownBy(() -> ScbType.parse("regional"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("regional");
  }

  @Test
  void mapsToAndFromTheCoreType() {
    assertThat(ScbType.CUSTOM.toCore()).isEqualTo(io.cassyx.core.api.astra.ScbType.CUSTOM);
    assertThat(ScbType.DEFAULT.toCore()).isEqualTo(io.cassyx.core.api.astra.ScbType.DEFAULT);
    assertThat(ScbType.fromCore(io.cassyx.core.api.astra.ScbType.CUSTOM)).isEqualTo(ScbType.CUSTOM);
    assertThat(ScbType.fromCore(null)).isEqualTo(ScbType.DEFAULT);
  }

  @Test
  @DisplayName("the download request never renders the Astra token")
  void downloadRequestRedactsItsToken() {
    AstraBundleDownloadRequest request =
        new AstraBundleDownloadRequest(
            "8f2b1c6e-2a55-4f47-9f2a-4c1c3f0d9a11",
            "AstraCS:abcdef:0123456789",
            "us-east1",
            ScbType.DEFAULT,
            null,
            true);

    assertThat(request.toString())
        .doesNotContain("AstraCS:abcdef:0123456789")
        .contains("<redacted>");
    assertThat(request.isForced()).isTrue();
    assertThat(request.scbTypeOrDefault()).isEqualTo(ScbType.DEFAULT);
  }

  @Test
  void deserialisesFromTheWireForm() throws Exception {
    AstraBundleDownloadRequest request =
        json.readValue(
            """
            {"connectionId":"c1","astraToken":"AstraCS:a:b","region":"us-east1","scbType":"custom",
             "domain":"cassandra.example.com"}
            """,
            AstraBundleDownloadRequest.class);

    assertThat(request.scbTypeOrDefault()).isEqualTo(ScbType.CUSTOM);
    assertThat(request.domain()).isEqualTo("cassandra.example.com");
    assertThat(request.isForced()).isFalse();
  }
}
