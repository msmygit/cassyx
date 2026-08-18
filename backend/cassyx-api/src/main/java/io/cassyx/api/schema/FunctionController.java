package io.cassyx.api.schema;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.UserDefinedAggregateDefinition;
import io.cassyx.core.api.schema.UserDefinedAggregateInfo;
import io.cassyx.core.api.schema.UserDefinedFunctionDefinition;
import io.cassyx.core.api.schema.UserDefinedFunctionInfo;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * UDFs and UDAs (plan section 4), gated on the {@code udfUda} capability - Astra DB does not offer
 * them (plan section 7.1).
 *
 * <p>Both are addressed by signature, because CQL functions are overloadable: {@code avg_state}
 * alone does not identify one object, {@code avg_state(double,int)} does.
 */
@RestController
@RequestMapping("/api/connections/{connectionId}/keyspaces/{keyspace}")
public class FunctionController {

  private static final String UNSUPPORTED =
      "User-defined functions and aggregates are unavailable on this cluster (Astra DB does not "
          + "support them).";

  private final SchemaReader reader;
  private final SchemaSessions sessions;
  private final DdlService ddl;

  public FunctionController(SchemaReader reader, SchemaSessions sessions, DdlService ddl) {
    this.reader = reader;
    this.sessions = sessions;
    this.ddl = ddl;
  }

  @GetMapping("/functions")
  public List<UserDefinedFunctionInfo> listFunctions(
      @PathVariable String connectionId, @PathVariable String keyspace) {
    return reader.functions(sessions.session(connectionId), keyspace);
  }

  @GetMapping("/functions/{functionSignature}")
  public UserDefinedFunctionInfo getFunction(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String functionSignature) {
    return reader.function(sessions.session(connectionId), keyspace, functionSignature);
  }

  @PostMapping("/functions")
  @ResponseStatus(HttpStatus.CREATED)
  public DdlExecutionResult createFunction(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @RequestBody UserDefinedFunctionDefinition definition) {
    sessions.require(connectionId, Capability.UDF_UDA, UNSUPPORTED);
    return ddl.apply(connectionId, ddl.generator().createFunction(keyspace, definition));
  }

  @DeleteMapping("/functions/{functionSignature}")
  public DdlExecutionResult dropFunction(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String functionSignature,
      @RequestParam(defaultValue = "true") boolean ifExists) {
    return ddl.apply(
        connectionId, ddl.generator().dropFunction(keyspace, functionSignature, ifExists));
  }

  @GetMapping("/aggregates")
  public List<UserDefinedAggregateInfo> listAggregates(
      @PathVariable String connectionId, @PathVariable String keyspace) {
    return reader.aggregates(sessions.session(connectionId), keyspace);
  }

  @GetMapping("/aggregates/{aggregateSignature}")
  public UserDefinedAggregateInfo getAggregate(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String aggregateSignature) {
    return reader.aggregate(sessions.session(connectionId), keyspace, aggregateSignature);
  }

  @PostMapping("/aggregates")
  @ResponseStatus(HttpStatus.CREATED)
  public DdlExecutionResult createAggregate(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @RequestBody UserDefinedAggregateDefinition definition) {
    sessions.require(connectionId, Capability.UDF_UDA, UNSUPPORTED);
    return ddl.apply(connectionId, ddl.generator().createAggregate(keyspace, definition));
  }

  @DeleteMapping("/aggregates/{aggregateSignature}")
  public DdlExecutionResult dropAggregate(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String aggregateSignature,
      @RequestParam(defaultValue = "true") boolean ifExists) {
    return ddl.apply(
        connectionId, ddl.generator().dropAggregate(keyspace, aggregateSignature, ifExists));
  }
}
