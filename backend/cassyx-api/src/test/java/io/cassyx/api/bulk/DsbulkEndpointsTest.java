package io.cassyx.api.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.bulk.api.dsbulk.DsbulkDistribution;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The bulk endpoints against a real application context.
 *
 * <p>What this actually proves, beyond the JSON shapes: that the DSBulk beans wire into the real
 * context at all, and that the application still boots when there is <b>no DSBulk distribution
 * installed</b>. That second one is the important one - a developer machine has no
 * {@code DSBULK_HOME}, and a container that refuses to start because the bulk loader is missing
 * would take the schema browser and the licence screen down with it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cassyx-dsbulk;DB_CLOSE_DELAY=-1",
      "cassyx.license.enforce=false",
      "cassyx.jobs.work-dir=${java.io.tmpdir}/cassyx-test-jobs",
      "cassyx.jobs.upload-dir=${java.io.tmpdir}/cassyx-test-uploads"
    })
class DsbulkEndpointsTest {

  private static final String CONNECTION = "11111111-2222-3333-4444-555555555555";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private DsbulkDistribution distribution;
  @Autowired private DsbulkJobEventStream events;

  @Test
  @DisplayName("the context boots and the DSBulk beans are present even with no distribution installed")
  void contextBootsWithoutDsbulk() {
    assertThat(distribution).isNotNull();
    assertThat(events).isNotNull();
  }

  @Test
  @DisplayName("deriveBulkDefaults returns explainable auto settings and the probe it used")
  void deriveDefaults() throws Exception {
    String body = mvc.perform(post("/api/connections/{id}/bulk/defaults", CONNECTION)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"operation":"UNLOAD","keyspace":"demo","table":"users","format":"CSV",
                 "overrides":{"batch":{"maxBatchStatements":64}}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operation").value("UNLOAD"))
        .andExpect(jsonPath("$.engine").value("DSBULK"))
        .andExpect(jsonPath("$.probe.nodeCount").isNumber())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

    JsonNode settings = json.readTree(body).get("settings");
    assertThat(settings.isArray()).isTrue();
    assertThat(settings.size()).isGreaterThan(5);

    JsonNode consistency = find(settings, "driver.basic.requestConsistency");
    assertThat(consistency.get("auto").asBoolean()).isTrue();
    assertThat(consistency.get("value").asText()).isEqualTo("LOCAL_ONE");
    // The rationale is the whole feature: an "auto" chip that cannot explain itself is a magic
    // number with a nicer font.
    assertThat(consistency.get("rationale").asText()).isNotBlank();
    assertThat(consistency.get("docsUrl").asText()).startsWith("https://");
    assertThat(consistency.get("group").asText()).isEqualTo("driver");

    JsonNode overridden = find(settings, "batch.maxBatchStatements");
    assertThat(overridden.get("auto").asBoolean()).isFalse();
    assertThat(overridden.get("value").asText()).isEqualTo("64");
  }

  @Test
  @DisplayName("previewBulkCommand returns the copyable command, the argv and the generated HOCON")
  void commandPreview() throws Exception {
    mvc.perform(post("/api/connections/{id}/bulk/command-preview", CONNECTION)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"operation":"UNLOAD","keyspace":"demo","table":"users","format":"CSV",
                 "sink":{"type":"VOLUME_PATH","path":"/data/exports/users"}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.command").value(org.hamcrest.Matchers.startsWith("dsbulk unload")))
        .andExpect(jsonPath("$.argv[0]").value("unload"))
        .andExpect(jsonPath("$.hocon").value(org.hamcrest.Matchers.containsString("dsbulk.schema.keyspace")))
        .andExpect(jsonPath("$.derivedSettings").isArray());
  }

  @Test
  @DisplayName("uploading a source file stages it server-side and returns a handle")
  void uploadSourceFile() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file", "users.csv", "text/csv", "id,email\n1,a@b.c\n".getBytes(StandardCharsets.UTF_8));

    mvc.perform(multipart("/api/bulk/uploads").file(file).param("format", "CSV"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.uploadId").value(org.hamcrest.Matchers.startsWith("up_")))
        .andExpect(jsonPath("$.fileName").value("users.csv"))
        .andExpect(jsonPath("$.sizeBytes").value(17))
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }

  @Test
  @DisplayName("job templates round-trip, and a template's settings are a starting point, not a ceiling")
  void jobTemplates() throws Exception {
    String created = mvc.perform(post("/api/job-templates")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Fast unload","description":"Oversplit, LZ4","operation":"UNLOAD",
                 "format":"CSV","engine":"DSBULK",
                 "dsbulkSettings":{"schema":{"splits":"16C"},"executor":{"maxInFlight":2048}}}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Fast unload"))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

    String id = json.readTree(created).get("id").asText();

    mvc.perform(get("/api/job-templates").param("operation", "UNLOAD"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id))
        .andExpect(jsonPath("$[0].description").value("Oversplit, LZ4"));

    mvc.perform(get("/api/job-templates/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dsbulkSettings.schema.splits").value("16C"));

    mvc.perform(get("/api/job-templates/{id}", "00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isNotFound());

    mvc.perform(delete("/api/job-templates/{id}", id)).andExpect(status().isNoContent());
    mvc.perform(delete("/api/job-templates/{id}", id)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("template settings are merged UNDER the caller's own overrides")
  void templateMerge() throws Exception {
    String created = mvc.perform(post("/api/job-templates")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Merge me","operation":"LOAD",
                 "dsbulkSettings":{"batch":{"maxBatchStatements":8},"schema":{"nullToUnset":false}}}
                """))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    String id = json.readTree(created).get("id").asText();

    DsbulkTemplateRepository repository = repository();
    Map<String, String> merged = repository.merge(id, Map.of("batch.maxBatchStatements", "99"));

    // A template a user could not override would just be a worse set of derived defaults.
    assertThat(merged).containsEntry("batch.maxBatchStatements", "99");
    assertThat(merged).containsEntry("schema.nullToUnset", "false");
    assertThat(repository.merge(null, Map.of("a", "b"))).containsEntry("a", "b");
  }

  @Autowired private DsbulkTemplateRepository templateRepository;

  private DsbulkTemplateRepository repository() {
    return templateRepository;
  }

  private static JsonNode find(JsonNode settings, String path) {
    for (JsonNode setting : settings) {
      if (path.equals(setting.get("path").asText())) {
        return setting;
      }
    }
    throw new AssertionError("No setting '" + path + "' in " + settings);
  }
}
