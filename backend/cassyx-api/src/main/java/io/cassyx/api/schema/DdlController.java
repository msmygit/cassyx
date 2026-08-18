package io.cassyx.api.schema;

import io.cassyx.core.api.schema.DdlExecuteRequest;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.DdlPreview;
import io.cassyx.core.api.schema.SchemaReader;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three endpoints that make "never execute generated DDL silently" enforceable (plan
 * section 4):
 *
 * <ul>
 *   <li>{@code /ddl/generate} - a pure function that turns a visual editor's state into CQL.
 *   <li>{@code /ddl/preview} - {@code describe} of an object that already exists.
 *   <li>{@code /ddl/execute} - runs CQL the user has seen and possibly edited.
 * </ul>
 */
@RestController
@RequestMapping("/api/connections/{connectionId}/ddl")
public class DdlController {

  private final DdlService ddl;
  private final SchemaReader reader;
  private final SchemaSessions sessions;

  public DdlController(DdlService ddl, SchemaReader reader, SchemaSessions sessions) {
    this.ddl = ddl;
    this.reader = reader;
    this.sessions = sessions;
  }

  /** Never touches the cluster - the connection id is only part of the route. */
  @PostMapping("/generate")
  public DdlPreview generate(
      @PathVariable String connectionId, @RequestBody DdlGenerateRequest request) {
    return ddl.generate(request);
  }

  /**
   * {@code TableMetadata#describe(true)} and its siblings. Driver 4.19.0 is required for correct
   * {@code vector<float, N>} rendering (CASSJAVA-2).
   */
  @PostMapping("/preview")
  public DdlPreview preview(
      @PathVariable String connectionId, @RequestBody DdlDescribeRequest request) {
    String cql =
        reader.describe(
            sessions.session(connectionId),
            request.identity(),
            request.withChildrenOrDefault(),
            request.formattedOrDefault());
    return new DdlPreview(cql, List.of(cql), List.of(), request.identity());
  }

  @PostMapping("/execute")
  public DdlExecutionResult execute(
      @PathVariable String connectionId, @RequestBody DdlExecuteRequest request) {
    return ddl.execute(connectionId, request);
  }
}
