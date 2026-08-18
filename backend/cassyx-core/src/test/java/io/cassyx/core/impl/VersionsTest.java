package io.cassyx.core.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class VersionsTest {

  @ParameterizedTest
  @CsvSource({
    "5.0.2, 5, 0",
    "4.1.3, 4, 1",
    "3.11.14, 3, 11",
    "4.0.0.6816, 4, 0",
    "6.8.35, 6, 8",
    "5.0.2-SNAPSHOT, 5, 0",
    "5, 5, 0",
    "2.1.22, 2, 1"
  })
  void readsMajorAndMinorFromTheVersionsClustersActuallyReport(
      String version, int major, int minor) {
    assertThat(Versions.major(version)).isEqualTo(major);
    assertThat(Versions.minor(version)).isEqualTo(minor);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "unknown", "not-a-version", "x.y.z"})
  @DisplayName("an unreadable version is older than everything - it must never unlock a feature")
  void failsClosed(String version) {
    assertThat(Versions.major(version)).isZero();
    assertThat(Versions.atLeast(version, 5, 0)).isFalse();
    assertThat(Versions.atLeast(version, 4, 0)).isFalse();
  }

  @Test
  void comparesMajorBeforeMinor() {
    assertThat(Versions.atLeast("6.8.35", 6, 8)).isTrue();
    assertThat(Versions.atLeast("6.7.11", 6, 8)).isFalse();
    assertThat(Versions.atLeast("6.9.0", 6, 8)).isTrue();
    assertThat(Versions.atLeast("7.0.0", 6, 8)).isTrue();
    assertThat(Versions.atLeast("5.9.9", 6, 8)).isFalse();
  }

  @Test
  void treatsAMissingSegmentAsZero() {
    assertThat(Versions.minor("5")).isZero();
    assertThat(Versions.atLeast("5", 5, 0)).isTrue();
    assertThat(Versions.atLeast("5", 5, 1)).isFalse();
  }
}
