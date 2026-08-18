package io.cassyx.core.api.astra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScbSelectorTest {

  @Test
  void nullScbTypeDefaultsInsteadOfThrowingNpe() {
    // Deviation 2: the reference calls scbType.toLowerCase() unguarded.
    assertThat(new ScbSelector("us-east1", null, null).scbType()).isEqualTo(ScbType.DEFAULT);
    assertThat(ScbType.parse(null)).isEqualTo(ScbType.DEFAULT);
    assertThat(ScbType.parse("  ")).isEqualTo(ScbType.DEFAULT);
    assertThat(ScbType.parse("CUSTOM")).isEqualTo(ScbType.CUSTOM);
  }

  @Test
  void rejectsThePhantomRegionType() {
    // Deviation 1: 'region' is NOT a bundle type. Reject it loudly instead of silently
    // falling through to 'default' the way the reference does.
    assertThatThrownBy(() -> ScbType.parse("region"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a secure connect bundle type");
    assertThat(ScbType.values()).containsExactly(ScbType.DEFAULT, ScbType.CUSTOM);
  }

  @Test
  void rejectsUnknownTypes() {
    assertThatThrownBy(() -> ScbType.parse("wat")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void domainIsRequiredIffCustom() {
    assertThatThrownBy(() -> new ScbSelector("us-east1", ScbType.CUSTOM, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ScbSelector("us-east1", ScbType.DEFAULT, "db.example.com"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blankRegionIsTreatedAsAbsentAndCacheKeyIsStable() {
    ScbSelector selector = new ScbSelector("  ", ScbType.DEFAULT, null);

    assertThat(selector.region()).isNull();
    assertThat(selector.regionOpt()).isEmpty();
    assertThat(selector.cacheKey("db1")).isEqualTo("db1|*|DEFAULT|");
    assertThat(ScbSelector.customDomain("US-East1", "d.example.com").cacheKey("db1"))
        .isEqualTo("db1|us-east1|CUSTOM|d.example.com");
  }
}
