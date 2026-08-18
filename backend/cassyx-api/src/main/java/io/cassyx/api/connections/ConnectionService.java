package io.cassyx.api.connections;

import io.cassyx.api.connections.dto.ConnectionRequest;
import io.cassyx.api.connections.dto.ConnectionResponse;
import io.cassyx.core.api.SessionRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Connection CRUD. Persistence and mapping only; sessions are {@link ConnectionSessionService}. */
@Service
public class ConnectionService {

  private final ConnectionRepository repository;
  private final ConnectionMapper mapper;
  private final SessionRegistry sessions;
  private final Clock clock;

  @Autowired
  public ConnectionService(
      ConnectionRepository repository, ConnectionMapper mapper, SessionRegistry sessions) {
    this(repository, mapper, sessions, Clock.systemUTC());
  }

  ConnectionService(
      ConnectionRepository repository,
      ConnectionMapper mapper,
      SessionRegistry sessions,
      Clock clock) {
    this.repository = repository;
    this.mapper = mapper;
    this.sessions = sessions;
    this.clock = clock;
  }

  public List<ConnectionResponse> list() {
    return repository.findAll().stream().map(this::toResponse).toList();
  }

  public ConnectionResponse get(String id) {
    return toResponse(require(id));
  }

  /** @throws ConnectionNotFoundException so the controller can answer 404 without a null check */
  public ConnectionRow require(String id) {
    return repository.findById(id).orElseThrow(() -> new ConnectionNotFoundException(id));
  }

  @Transactional
  public ConnectionResponse create(ConnectionRequest request) {
    if (repository.existsByName(request.name().trim(), null)) {
      throw new DuplicateConnectionNameException(request.name().trim(), null);
    }
    Instant now = clock.instant();
    ConnectionRow row =
        mapper.apply(request, new ConnectionRow().id(UUID.randomUUID().toString()));
    row.createdAt(now).updatedAt(now);
    repository.insert(row);
    return toResponse(row);
  }

  /**
   * Full replacement, except for the binary material.
   *
   * <p>Uploaded stores and the secure connect bundle are managed through their own endpoints and are
   * untouched here - the contract says so, and a PUT that silently dropped an uploaded bundle would
   * make editing a connection's name a destructive act.
   */
  @Transactional
  public ConnectionResponse update(String id, ConnectionRequest request) {
    ConnectionRow existing = require(id);
    if (repository.existsByName(request.name().trim(), id)) {
      throw new DuplicateConnectionNameException(request.name().trim(), null);
    }
    ConnectionRow row = mapper.apply(request, existing);
    row.updatedAt(clock.instant());
    repository.update(row);
    return toResponse(row);
  }

  /** Persists a row the bundle/store services have mutated. */
  @Transactional
  public ConnectionResponse save(ConnectionRow row) {
    row.updatedAt(clock.instant());
    repository.update(row);
    return toResponse(row);
  }

  public boolean delete(String id) {
    require(id);
    return repository.delete(id);
  }

  public void touchLastConnected(String id) {
    repository.touchLastConnected(id, clock.instant());
  }

  public ConnectionResponse toResponse(ConnectionRow row) {
    return mapper.toResponse(row, sessions.isConnected(row.id()));
  }
}
