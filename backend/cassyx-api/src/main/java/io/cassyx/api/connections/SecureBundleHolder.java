package io.cassyx.api.connections;

import io.cassyx.core.api.ConnectionSpec;
import java.nio.file.Path;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Hands the materialised secure connect bundle to the shared {@code SessionFactory}.
 *
 * <p>The session factory is a singleton built once at startup, but the bundle is per-connect: it is
 * decrypted out of H2 into a temp file, used for the moment the driver builds the session, and then
 * deleted. Something has to bridge those two lifetimes.
 *
 * <p>A {@link ThreadLocal} does it, and is safe here because the whole sequence - materialise, open,
 * delete - happens synchronously on one request thread inside
 * {@link ConnectionSessionService#connect}. The alternative, threading a resolver argument through
 * {@code SessionRegistry.open}, would put an Astra storage concern into the interface that three
 * other workstreams code against.
 *
 * <p>{@link #clear()} runs in a {@code finally} so a failed connect cannot leave a stale path
 * pointing at a deleted file for the next request on that thread.
 */
@Component
public class SecureBundleHolder {

  private final ThreadLocal<Path> current = new ThreadLocal<>();

  public void set(Path bundle) {
    if (bundle == null) {
      current.remove();
    } else {
      current.set(bundle);
    }
  }

  public Path get() {
    return current.get();
  }

  public void clear() {
    current.remove();
  }

  /** The resolver handed to {@code CoreFactory.sessionFactory(...)}. */
  public Function<ConnectionSpec, Path> resolver() {
    return spec -> current.get();
  }
}
