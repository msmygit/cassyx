package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * User-reviewed CQL to execute. Split with the real CQL lexer, never {@code split(";")} - UDF
 * bodies, string literals and comments all contain semicolons (plan section 5.1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DdlExecuteRequest(
    String cql, Boolean stopOnError, Boolean awaitSchemaAgreement, SchemaIdentity affectedIdentity) {

  public boolean stopOnErrorOrDefault() {
    return stopOnError == null || stopOnError;
  }

  public boolean awaitSchemaAgreementOrDefault() {
    return awaitSchemaAgreement == null || awaitSchemaAgreement;
  }
}
