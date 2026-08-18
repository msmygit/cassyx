package io.cassyx.core.impl.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import io.cassyx.core.api.CqlStatement;
import io.cassyx.core.api.CqlStatementSplitter;
import io.cassyx.core.api.schema.DdlExecuteRequest;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.DdlExecutor;
import io.cassyx.core.api.schema.DdlPreview;
import io.cassyx.core.api.schema.InvalidDefinitionException;
import io.cassyx.core.api.schema.SchemaIdentity;
import io.cassyx.core.impl.DefaultCqlStatementSplitter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes CQL the user has already reviewed.
 *
 * <p>Scripts are carved up with the real CQL lexer rather than {@code split(";")}: a UDF body, a
 * string literal or a comment all contain semicolons, and a naive split silently truncates the
 * statement (plan section 5.1).
 */
public final class SessionDdlExecutor implements DdlExecutor {

  private static final long NANOS_PER_MILLI = 1000000L;

  private final CqlStatementSplitter splitter;

  public SessionDdlExecutor() {
    this(new DefaultCqlStatementSplitter());
  }

  public SessionDdlExecutor(CqlStatementSplitter splitter) {
    this.splitter = splitter;
  }

  @Override
  public DdlExecutionResult execute(CqlSession session, DdlExecuteRequest request) {
    if (request == null || request.cql() == null || request.cql().isBlank()) {
      throw new InvalidDefinitionException("cql", "no CQL to execute");
    }
    List<String> statements =
        splitter.split(request.cql()).stream()
            .map(CqlStatement::text)
            .filter(text -> !text.isBlank())
            .toList();
    if (statements.isEmpty()) {
      throw new InvalidDefinitionException("cql", "no executable statement found in the script");
    }
    return run(
        session,
        statements,
        request.stopOnErrorOrDefault(),
        request.awaitSchemaAgreementOrDefault(),
        request.affectedIdentity());
  }

  @Override
  public DdlExecutionResult execute(
      CqlSession session, DdlPreview preview, boolean awaitSchemaAgreement) {
    return run(session, preview.statements(), true, awaitSchemaAgreement, preview.targetIdentity());
  }

  private DdlExecutionResult run(
      CqlSession session,
      List<String> statements,
      boolean stopOnError,
      boolean awaitSchemaAgreement,
      SchemaIdentity identity) {
    long started = System.nanoTime();
    Set<String> warnings = new LinkedHashSet<>();
    List<String> executed = new ArrayList<>();
    boolean success = true;

    for (String statement : statements) {
      try {
        ResultSet result = session.execute(SimpleStatement.newInstance(statement));
        warnings.addAll(result.getExecutionInfo().getWarnings());
        executed.add(DdlSecrets.redact(statement));
      } catch (RuntimeException e) {
        executed.add(DdlSecrets.redact(statement));
        success = false;
        if (stopOnError) {
          throw e;
        }
        warnings.add(e.getMessage());
      }
    }

    boolean agreement = false;
    if (awaitSchemaAgreement) {
      agreement = session.checkSchemaAgreement();
    }
    long elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI;
    return new DdlExecutionResult(
        success, executed, executed.size(), elapsed, agreement, List.copyOf(warnings), identity);
  }
}
