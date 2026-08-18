package io.cassyx.bulk.api;

import com.datastax.oss.driver.api.core.CqlSession;

/**
 * Token-range parallel unload (plan section 5.2). Implementations must {@code unwrap()} every token
 * range before querying - CQL cannot express the wrapping range around the ring minimum, and
 * querying it silently returns wrong results.
 */
public interface UnloadEngine {

  UnloadResult unload(CqlSession session, UnloadRequest request, ProgressListener listener);
}
