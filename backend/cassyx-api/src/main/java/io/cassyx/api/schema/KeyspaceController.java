package io.cassyx.api.schema;

import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.KeyspaceDefinition;
import io.cassyx.core.api.schema.KeyspaceInfo;
import io.cassyx.core.api.schema.SchemaReader;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Keyspace CRUD (plan section 4): SimpleStrategy vs NetworkTopologyStrategy, durable writes. */
@RestController
@RequestMapping("/api/connections/{connectionId}/keyspaces")
public class KeyspaceController {

  private final SchemaReader reader;
  private final SchemaSessions sessions;
  private final DdlService ddl;

  public KeyspaceController(SchemaReader reader, SchemaSessions sessions, DdlService ddl) {
    this.reader = reader;
    this.sessions = sessions;
    this.ddl = ddl;
  }

  @GetMapping
  public List<KeyspaceInfo> list(
      @PathVariable String connectionId,
      @RequestParam(defaultValue = "false") boolean includeSystem) {
    return reader.keyspaces(sessions.session(connectionId), includeSystem);
  }

  @GetMapping("/{keyspace}")
  public KeyspaceInfo get(@PathVariable String connectionId, @PathVariable String keyspace) {
    return reader.keyspace(sessions.session(connectionId), keyspace);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DdlExecutionResult create(
      @PathVariable String connectionId, @RequestBody KeyspaceDefinition definition) {
    return ddl.apply(connectionId, ddl.generator().createKeyspace(definition));
  }

  @PutMapping("/{keyspace}")
  public DdlExecutionResult alter(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @RequestBody KeyspaceDefinition definition) {
    reader.keyspace(sessions.session(connectionId), keyspace);
    return ddl.apply(connectionId, ddl.generator().alterKeyspace(keyspace, definition));
  }

  @DeleteMapping("/{keyspace}")
  public DdlExecutionResult drop(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @RequestParam(defaultValue = "true") boolean ifExists) {
    return ddl.apply(connectionId, ddl.generator().dropKeyspace(keyspace, ifExists));
  }
}
