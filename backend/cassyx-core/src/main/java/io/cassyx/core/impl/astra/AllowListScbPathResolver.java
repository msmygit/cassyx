package io.cassyx.core.impl.astra;

import io.cassyx.core.api.astra.ScbPathException;
import io.cassyx.core.api.astra.ScbPathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@link ScbPathResolver} that confines server-side bundle paths to an allow-list root and proves
 * the target really is a secure connect bundle before a connection is attempted.
 *
 * <p>Symlinks are followed during canonicalisation ({@code toRealPath}) and the result is re-checked
 * against the root, so a symlink inside the root cannot be used to escape it.
 */
public final class AllowListScbPathResolver implements ScbPathResolver {

  /** Entries every Astra secure connect bundle carries. */
  private static final List<String> REQUIRED_ENTRIES = List.of("config.json");

  private static final List<String> EXPECTED_ANY =
      List.of("identity.jks", "trustStore.jks", "cert", "key", "ca.crt");

  private final Path root;

  public AllowListScbPathResolver(Path root) {
    this.root = normalizeRoot(root);
  }

  /** Reads the root from {@code CASSYX_SCB_PATH_ROOT}, falling back to {@code /etc/cassyx/scb}. */
  public static AllowListScbPathResolver fromEnvironment() {
    String configured = System.getenv(ROOT_ENV_VAR);
    return new AllowListScbPathResolver(
        Paths.get(configured == null || configured.isBlank() ? DEFAULT_ROOT : configured));
  }

  private static Path normalizeRoot(Path root) {
    Path absolute = root.toAbsolutePath().normalize();
    try {
      return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      return absolute;
    }
  }

  public Path root() {
    return root;
  }

  @Override
  public Path resolve(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      throw new ScbPathException("Secure connect bundle path is empty");
    }
    Path requested;
    try {
      Path raw = Paths.get(candidate.trim());
      requested = raw.isAbsolute() ? raw : root.resolve(raw);
    } catch (InvalidPathException e) {
      throw new ScbPathException("Invalid secure connect bundle path", e);
    }

    Path normalized = requested.normalize();
    if (!normalized.startsWith(root)) {
      throw new ScbPathException(
          "Secure connect bundle path is outside the allowed root " + root + " (set "
              + ROOT_ENV_VAR + " to change it)");
    }

    Path real;
    try {
      real = normalized.toRealPath();
    } catch (IOException e) {
      throw new ScbPathException("Secure connect bundle not found at " + normalized, e);
    }
    if (!real.startsWith(root)) {
      throw new ScbPathException(
          "Secure connect bundle path resolves outside the allowed root " + root);
    }
    if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
      throw new ScbPathException("Secure connect bundle at " + real + " is not a readable file");
    }
    verifyBundle(real);
    return real;
  }

  /** Visible for testing: fails fast with a clear message instead of an obscure TLS error later. */
  public static void verifyBundle(Path file) {
    try (ZipFile zip = new ZipFile(file.toFile())) {
      for (String required : REQUIRED_ENTRIES) {
        ZipEntry entry = zip.getEntry(required);
        if (entry == null) {
          throw new ScbPathException(
              "File " + file + " is not a secure connect bundle: missing entry '" + required + "'");
        }
      }
      boolean hasAny = EXPECTED_ANY.stream().anyMatch(name -> zip.getEntry(name) != null);
      if (!hasAny) {
        throw new ScbPathException(
            "File " + file + " is not a secure connect bundle: no keystore/certificate entries");
      }
    } catch (IOException e) {
      throw new ScbPathException("File " + file + " is not a readable zip archive", e);
    }
  }
}
