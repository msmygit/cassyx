package io.cassyx.bulk.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.io.OutputStream;

/**
 * Token-range parallel unload (plan section 5.2).
 *
 * <p>Implementations must {@code unwrap()} every token range before querying - CQL cannot express
 * the wrapping range around the ring minimum, and querying it silently returns wrong results.
 *
 * <p>The engine takes a {@link CqlSession} as a parameter and nothing else: cassyx-bulk is usable
 * in an unrelated Java project with no Spring, no web layer and no session registry (plan section
 * 2.1).
 */
public interface UnloadEngine {

  /** Unloads to the sink resolved from {@link UnloadRequest#target()}. */
  default UnloadResult unload(CqlSession session, UnloadRequest request, ProgressListener listener) {
    return unload(session, request, listener, Cancellation.never());
  }

  UnloadResult unload(
      CqlSession session,
      UnloadRequest request,
      ProgressListener listener,
      Cancellation cancellation);

  /**
   * Unloads straight into {@code out} - the HTTP streaming-download path. The stream is never
   * buffered in full; a 50M-row unload holds flat memory.
   *
   * <p>The caller owns {@code out} and closes it.
   */
  UnloadResult unloadTo(
      CqlSession session,
      UnloadRequest request,
      OutputStream out,
      ProgressListener listener,
      Cancellation cancellation);

  /** Which read strategy this engine would pick for {@code session} (plan section 7.1 fallback). */
  ScanStrategy strategyFor(CqlSession session, UnloadRequest request);
}
