package io.cassyx.api.query;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Query history and saved / favourite scripts - the {@code query} tag's persistence endpoints. */
@RestController
public class QueryLibraryController {

  private final QueryHistoryRepository history;
  private final SavedScriptRepository scripts;

  public QueryLibraryController(QueryHistoryRepository history, SavedScriptRepository scripts) {
    this.history = history;
    this.scripts = scripts;
  }

  @GetMapping("/api/query/history")
  public QueryDtos.QueryHistoryPage listQueryHistory(
      @RequestParam(required = false) String connectionId,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {

    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("The `limit` parameter must be between 1 and 500.");
    }
    if (offset < 0) {
      throw new IllegalArgumentException("The `offset` parameter must not be negative.");
    }
    return history.list(connectionId, q, limit, offset);
  }

  @DeleteMapping("/api/query/history")
  public ResponseEntity<Void> clearQueryHistory(@RequestParam(required = false) String connectionId) {
    history.clear(connectionId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/query/scripts")
  public List<QueryDtos.SavedScript> listSavedScripts(@RequestParam(required = false) String folder) {
    return scripts.list(folder);
  }

  @PostMapping("/api/query/scripts")
  public ResponseEntity<QueryDtos.SavedScript> createSavedScript(
      @Valid @RequestBody QueryDtos.SavedScriptRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(scripts.create(request));
  }

  @GetMapping("/api/query/scripts/{scriptId}")
  public QueryDtos.SavedScript getSavedScript(@PathVariable String scriptId) {
    return scripts.find(scriptId).orElseThrow(() -> notFound(scriptId));
  }

  @PutMapping("/api/query/scripts/{scriptId}")
  public QueryDtos.SavedScript updateSavedScript(
      @PathVariable String scriptId, @Valid @RequestBody QueryDtos.SavedScriptRequest request) {
    return scripts.update(scriptId, request).orElseThrow(() -> notFound(scriptId));
  }

  @DeleteMapping("/api/query/scripts/{scriptId}")
  public ResponseEntity<Void> deleteSavedScript(@PathVariable String scriptId) {
    if (!scripts.delete(scriptId)) {
      throw notFound(scriptId);
    }
    return ResponseEntity.noContent().build();
  }

  private static ResponseStatusException notFound(String scriptId) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved script " + scriptId + ".");
  }
}
