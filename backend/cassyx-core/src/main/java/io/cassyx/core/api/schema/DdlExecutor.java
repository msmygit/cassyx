package io.cassyx.core.api.schema;

import com.datastax.oss.driver.api.core.CqlSession;

/**
 * Executes CQL the user has reviewed.
 *
 * <p>The preview/execute split is deliberate: nothing here generates DDL, so no path exists that
 * runs a statement the user never saw (plan section 4).
 */
public interface DdlExecutor {

  DdlExecutionResult execute(CqlSession session, DdlExecuteRequest request);

  /** Executes an already-generated preview, after the user accepted it unmodified. */
  DdlExecutionResult execute(CqlSession session, DdlPreview preview, boolean awaitSchemaAgreement);
}
