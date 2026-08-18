package io.cassyx.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan section 2.3: secrets are write-only. A response DTO that accidentally embeds a
 * {@link Secret} must still not expose it, and a log line that interpolates one must not leak it.
 */
class SecretTest {

  private static final String SENTINEL = "sup3r-s3cret-value";

  @Test
  void toStringRedacts() {
    assertThat(Secret.of(SENTINEL)).hasToString(Secret.REDACTED);
    assertThat(Secret.empty()).hasToString("<none>");
    assertThat("password=" + Secret.of(SENTINEL)).doesNotContain(SENTINEL);
  }

  @Test
  void jacksonSerialisesAsNullEvenInsideADto() throws Exception {
    ConnectionSpec spec =
        new ConnectionSpec(
            "prod",
            List.of("10.0.0.1:9042"),
            "dc1",
            "svc",
            Secret.of(SENTINEL),
            null,
            null,
            null);

    String json = new ObjectMapper().writeValueAsString(spec);

    assertThat(json).doesNotContain(SENTINEL).contains("\"password\":null");
    assertThat(spec.hasPassword()).isTrue();
    assertThat(spec.toString()).doesNotContain(SENTINEL);
  }

  @Test
  void revealIsTheOnlyWayOut() {
    Secret secret = Secret.of(SENTINEL);

    assertThat(secret.reveal()).isEqualTo(SENTINEL);
    assertThat(secret.revealChars()).isEqualTo(SENTINEL.toCharArray());
    assertThat(secret.isPresent()).isTrue();
    assertThat(Secret.of((String) null).isEmpty()).isTrue();
    assertThat(Secret.of(new char[0]).isEmpty()).isTrue();
    assertThat(Secret.empty().reveal()).isNull();
    assertThat(Secret.empty().revealChars()).isEmpty();
  }

  @Test
  void equalityIsValueBased() {
    assertThat(Secret.of(SENTINEL)).isEqualTo(Secret.of(SENTINEL.toCharArray()));
    assertThat(Secret.of(SENTINEL)).hasSameHashCodeAs(Secret.of(SENTINEL));
    assertThat(Secret.of(SENTINEL)).isNotEqualTo(Secret.of("other")).isNotEqualTo("not a secret");
  }
}
