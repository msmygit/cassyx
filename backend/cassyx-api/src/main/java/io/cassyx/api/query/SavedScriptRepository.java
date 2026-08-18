package io.cassyx.api.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Saved / favourite scripts with folders (plan section 8).
 *
 * <p>The contract models a folder as a path string while the Phase 0 schema models it as a row in
 * {@code cassyx_script_folder}. This repository reconciles the two by materialising one folder row
 * per distinct path, so the tree the schema was designed for stays available without a migration
 * that another workstream would have to merge.
 */
@Repository
public class SavedScriptRepository {

  private final JdbcTemplate jdbc;

  public SavedScriptRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String SELECT =
      "SELECT s.id, s.name, s.body, s.favorite, s.connection_id, s.keyspace_name, s.created_at,"
          + " s.updated_at, f.name AS folder_path"
          + " FROM cassyx_saved_script s LEFT JOIN cassyx_script_folder f ON f.id = s.folder_id";

  private static final RowMapper<QueryDtos.SavedScript> MAPPER =
      (rs, rowNum) ->
          new QueryDtos.SavedScript(
              rs.getString("id"),
              rs.getString("name"),
              rs.getString("body"),
              rs.getString("folder_path"),
              rs.getBoolean("favorite"),
              rs.getString("connection_id"),
              rs.getString("keyspace_name"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());

  public List<QueryDtos.SavedScript> list(String folder) {
    if (folder == null || folder.isBlank()) {
      return jdbc.query(SELECT + " ORDER BY s.name", MAPPER);
    }
    return jdbc.query(SELECT + " WHERE f.name = ? ORDER BY s.name", MAPPER, folder);
  }

  public Optional<QueryDtos.SavedScript> find(String id) {
    try {
      return Optional.ofNullable(jdbc.queryForObject(SELECT + " WHERE s.id = ?", MAPPER, id));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public QueryDtos.SavedScript create(QueryDtos.SavedScriptRequest request) {
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO cassyx_saved_script (id, name, folder_id, connection_id, keyspace_name, body,"
            + " favorite, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        id,
        request.name(),
        folderId(request.folder()),
        knownConnection(request.connectionId()),
        request.description(),
        request.cql(),
        Boolean.TRUE.equals(request.favourite()),
        Timestamp.from(now),
        Timestamp.from(now));
    return find(id).orElseThrow();
  }

  public Optional<QueryDtos.SavedScript> update(String id, QueryDtos.SavedScriptRequest request) {
    int updated =
        jdbc.update(
            "UPDATE cassyx_saved_script SET name = ?, folder_id = ?, connection_id = ?,"
                + " keyspace_name = ?, body = ?, favorite = ?, updated_at = ? WHERE id = ?",
            request.name(),
            folderId(request.folder()),
            knownConnection(request.connectionId()),
            request.description(),
            request.cql(),
            Boolean.TRUE.equals(request.favourite()),
            Timestamp.from(Instant.now()),
            id);
    return updated == 0 ? Optional.empty() : find(id);
  }

  public boolean delete(String id) {
    return jdbc.update("DELETE FROM cassyx_saved_script WHERE id = ?", id) > 0;
  }

  /** Finds or creates the folder row for a virtual path. */
  private String folderId(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    List<String> existing =
        jdbc.queryForList("SELECT id FROM cassyx_script_folder WHERE name = ?", String.class, path);
    if (!existing.isEmpty()) {
      return existing.get(0);
    }
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO cassyx_script_folder (id, name, parent_id, created_at) VALUES (?,?,?,?)",
        id,
        path,
        null,
        Timestamp.from(Instant.now()));
    return id;
  }

  private String knownConnection(String connectionId) {
    if (connectionId == null || connectionId.isBlank()) {
      return null;
    }
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cassyx_connection WHERE id = ?", Integer.class, connectionId);
    return count != null && count > 0 ? connectionId : null;
  }
}
