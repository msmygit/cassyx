package io.cassyx.api.schema;

import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.UserDefinedTypeAlteration;
import io.cassyx.core.api.schema.UserDefinedTypeDefinition;
import io.cassyx.core.api.schema.UserDefinedTypeInfo;
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

/** UDTs (plan section 4). {@code usedBy} explains why a drop may be refused. */
@RestController
@RequestMapping("/api/connections/{connectionId}/keyspaces/{keyspace}/types")
public class UserDefinedTypeController {

  private final SchemaReader reader;
  private final SchemaSessions sessions;
  private final DdlService ddl;

  public UserDefinedTypeController(SchemaReader reader, SchemaSessions sessions, DdlService ddl) {
    this.reader = reader;
    this.sessions = sessions;
    this.ddl = ddl;
  }

  @GetMapping
  public List<UserDefinedTypeInfo> list(
      @PathVariable String connectionId, @PathVariable String keyspace) {
    return reader.types(sessions.session(connectionId), keyspace);
  }

  @GetMapping("/{type}")
  public UserDefinedTypeInfo get(
      @PathVariable String connectionId, @PathVariable String keyspace, @PathVariable String type) {
    return reader.type(sessions.session(connectionId), keyspace, type);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DdlExecutionResult create(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @RequestBody UserDefinedTypeDefinition definition) {
    return ddl.apply(connectionId, ddl.generator().createType(keyspace, definition));
  }

  @PutMapping("/{type}")
  public DdlExecutionResult alter(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String type,
      @RequestBody UserDefinedTypeAlteration alteration) {
    reader.type(sessions.session(connectionId), keyspace, type);
    return ddl.apply(connectionId, ddl.generator().alterType(keyspace, type, alteration));
  }

  @DeleteMapping("/{type}")
  public DdlExecutionResult drop(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String type,
      @RequestParam(defaultValue = "true") boolean ifExists) {
    return ddl.apply(connectionId, ddl.generator().dropType(keyspace, type, ifExists));
  }
}
