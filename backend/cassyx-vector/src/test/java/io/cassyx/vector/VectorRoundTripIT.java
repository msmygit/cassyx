package io.cassyx.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.data.CqlVector;
import io.cassyx.core.testsupport.IntegrationTestBase;
import io.cassyx.vector.api.VectorEncoding;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>CASSANDRA-19333 guard, through a real cluster.</b>
 *
 * <p>{@code VectorCodecFidelityTest} pins the codec in isolation; this pins the whole path -
 * bind, protocol frame, storage, read back - at the full 1536 dimensions the demo dataset uses.
 * The assertion is element-by-element raw-bit equality, because the failure mode of
 * CASSANDRA-19333 was silent: no exception, no wrong size, just wrong numbers.
 */
class VectorRoundTripIT extends IntegrationTestBase {

  private static final int DIMENSIONS = 1536;
  private static final String KEYSPACE = "cassyx_vector_it";
  private static final String TABLE = "roundtrip";

  @BeforeAll
  static void createSchema() {
    // NOT IntegrationTestBase.ensureKeyspace: it uses the driver's 2s default request timeout,
    // which the first schema change against a cold container regularly exceeds.
    ddl(
        "CREATE KEYSPACE IF NOT EXISTS "
            + KEYSPACE
            + " WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
    ddl(
        "CREATE TABLE IF NOT EXISTS "
            + KEYSPACE
            + "."
            + TABLE
            + " (id uuid PRIMARY KEY, embedding vector<float, "
            + DIMENSIONS
            + ">)");
  }

  /**
   * Schema changes need far longer than the driver's 2s default request timeout, especially the
   * first ones against a freshly started container.
   */
  private static void ddl(String cql) {
    session()
        .execute(
            SimpleStatement.builder(cql).setTimeout(java.time.Duration.ofSeconds(60)).build());
  }


  @Test
  @DisplayName("A 1536-dimension vector survives insert and read with exact bit fidelity")
  void roundTripsThroughTheCluster() {
    List<Float> original = VectorCodecFidelityTest.fixture();
    UUID id = UUID.randomUUID();

    session()
        .execute(
            SimpleStatement.builder(
                    "INSERT INTO " + KEYSPACE + "." + TABLE + " (id, embedding) VALUES (?, ?)")
                .addPositionalValues(id, CqlVector.newInstance(original))
                .build());

    Row row =
        session()
            .execute(
                SimpleStatement.builder(
                        "SELECT embedding FROM " + KEYSPACE + "." + TABLE + " WHERE id = ?")
                    .addPositionalValue(id)
                    .build())
            .one();

    assertThat(row).isNotNull();
    List<Float> readBack = VectorEncoding.toFloatList(row.getObject("embedding"));
    assertThat(readBack).isNotNull().hasSize(DIMENSIONS);

    for (int i = 0; i < DIMENSIONS; i++) {
      assertThat(Float.floatToRawIntBits(readBack.get(i)))
          .as(
              "element %d corrupted in the cluster round trip: expected %s, got %s",
              i, original.get(i), readBack.get(i))
          .isEqualTo(Float.floatToRawIntBits(original.get(i)));
    }
  }

  @Test
  @DisplayName("describe still renders the column as vector<float, 1536> (CASSJAVA-2)")
  void describeRendersTheVectorType() {
    String ddl =
        session()
            .getMetadata()
            .getKeyspace(KEYSPACE)
            .orElseThrow()
            .getTable(TABLE)
            .orElseThrow()
            .describe(true);

    // Workstream B owns the full describe regression suite; this is the vector-shaped smoke check
    // that our driver pin is high enough to have CASSJAVA-2 fixed.
    assertThat(ddl).contains("vector<float, " + DIMENSIONS + ">");
  }
}
