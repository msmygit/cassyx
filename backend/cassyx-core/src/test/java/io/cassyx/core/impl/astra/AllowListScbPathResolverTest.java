package io.cassyx.core.impl.astra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.core.api.astra.ScbPathException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllowListScbPathResolverTest {

  @TempDir Path tmp;

  private static Path writeBundle(Path file, Map<String, String> entries) throws IOException {
    Files.createDirectories(file.getParent());
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, String> e : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return file;
  }

  private static Map<String, String> validBundleEntries() {
    return Map.of("config.json", "{\"host\":\"x\"}", "identity.jks", "binary", "ca.crt", "pem");
  }

  @Test
  void acceptsValidBundleInsideRootByRelativeName() throws IOException {
    Path root = Files.createDirectory(tmp.resolve("scb"));
    writeBundle(root.resolve("prod.zip"), validBundleEntries());

    Path resolved = new AllowListScbPathResolver(root).resolve("prod.zip");

    assertThat(resolved).isEqualTo(root.resolve("prod.zip").toRealPath());
  }

  @Test
  void rejectsPathTraversalOutsideRoot() throws IOException {
    Path root = Files.createDirectory(tmp.resolve("scb"));
    writeBundle(tmp.resolve("outside/secret.zip"), validBundleEntries());
    AllowListScbPathResolver resolver = new AllowListScbPathResolver(root);

    assertThatThrownBy(() -> resolver.resolve("../outside/secret.zip"))
        .isInstanceOf(ScbPathException.class)
        .hasMessageContaining("outside the allowed root");
    assertThatThrownBy(() -> resolver.resolve("/etc/passwd"))
        .isInstanceOf(ScbPathException.class)
        .hasMessageContaining("outside the allowed root");
  }

  @Test
  void rejectsSymlinkEscapingRoot() throws IOException {
    Path root = Files.createDirectory(tmp.resolve("scb"));
    Path outside = writeBundle(tmp.resolve("outside/secret.zip"), validBundleEntries());
    try {
      Files.createSymbolicLink(root.resolve("link.zip"), outside);
    } catch (UnsupportedOperationException | IOException e) {
      return; // filesystem without symlink support; nothing to assert
    }

    assertThatThrownBy(() -> new AllowListScbPathResolver(root).resolve("link.zip"))
        .isInstanceOf(ScbPathException.class)
        .hasMessageContaining("outside the allowed root");
  }

  @Test
  void rejectsMissingFileWithAClearMessage() throws IOException {
    Path root = Files.createDirectory(tmp.resolve("scb"));

    assertThatThrownBy(() -> new AllowListScbPathResolver(root).resolve("nope.zip"))
        .isInstanceOf(ScbPathException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void rejectsNonZipAndIncompleteBundle() throws IOException {
    Path root = Files.createDirectory(tmp.resolve("scb"));
    Files.writeString(root.resolve("plain.zip"), "not a zip");
    writeBundle(root.resolve("partial.zip"), Map.of("config.json", "{}"));
    AllowListScbPathResolver resolver = new AllowListScbPathResolver(root);

    assertThatThrownBy(() -> resolver.resolve("plain.zip"))
        .isInstanceOf(ScbPathException.class)
        .hasMessageContaining("readable zip");
    assertThatThrownBy(() -> resolver.resolve("partial.zip"))
        .isInstanceOf(ScbPathException.class)
        .hasMessageContaining("keystore/certificate");
  }

  @Test
  void rejectsBlankPath() {
    assertThatThrownBy(() -> new AllowListScbPathResolver(tmp).resolve(" "))
        .isInstanceOf(ScbPathException.class);
  }
}
