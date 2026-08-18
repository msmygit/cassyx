package io.cassyx.api.bulk;

import io.cassyx.api.bulk.DsbulkDtos.BulkCommandPreview;
import io.cassyx.api.bulk.DsbulkDtos.BulkCommandPreviewRequest;
import io.cassyx.api.bulk.DsbulkDtos.BulkDefaultsRequest;
import io.cassyx.api.bulk.DsbulkDtos.BulkProbeResult;
import io.cassyx.api.bulk.DsbulkDtos.BulkSink;
import io.cassyx.api.bulk.DsbulkDtos.BulkSource;
import io.cassyx.api.bulk.DsbulkDtos.DerivedSetting;
import io.cassyx.api.bulk.DsbulkDtos.DerivedSettingsResponse;
import io.cassyx.bulk.api.dsbulk.DsbulkFactory;
import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkPlan;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints that make DSBulk's settings surface usable rather than merely exposed
 * (plan section 5.3).
 *
 * <ul>
 *   <li>{@code POST /bulk/defaults} - probe the cluster and return every setting cassyx would
 *       apply, each with {@code auto}, the upstream default, and a <b>rationale</b>. That rationale
 *       is the whole point: the UI renders an editable chip that explains WHY, so a user can
 *       disagree with a decision instead of merely overriding a magic number.
 *   <li>{@code POST /bulk/command-preview} - the exact, copyable {@code dsbulk} invocation plus the
 *       generated HOCON. The same planner produces this and the command that actually runs, so the
 *       preview cannot drift from reality - which is what lets this UI double as a DSBulk command
 *       builder for people who will run the job somewhere else entirely.
 * </ul>
 */
@RestController
public class DsbulkPlanningController {

  private final DsbulkJobService jobs;

  public DsbulkPlanningController(DsbulkJobService jobs) {
    this.jobs = jobs;
  }

  @PostMapping("/api/connections/{connectionId}/bulk/defaults")
  public DerivedSettingsResponse deriveBulkDefaults(
      @PathVariable String connectionId, @RequestBody BulkDefaultsRequest request) {
    DsbulkOperation operation = DsbulkOperation.parse(request.operation());
    DsbulkJobSpec spec = new DsbulkJobSpec(
        operation,
        request.keyspace(),
        request.table(),
        request.query(),
        connectorFor(request.format()),
        null,
        null,
        false,
        null,
        10,
        DsbulkSettingsFlattener.flatten(request.overrides()));

    DsbulkProbe probe = jobs.probe(connectionId, request.keyspace(), request.table());
    DsbulkPlan plan = DsbulkFactory.plan(spec, probe, null, null);

    return new DerivedSettingsResponse(
        operation.name(),
        "DSBULK",
        DerivedSetting.from(plan.settings()),
        BulkProbeResult.from(probe),
        plan.warnings());
  }

  @PostMapping("/api/connections/{connectionId}/bulk/command-preview")
  public BulkCommandPreview previewBulkCommand(
      @PathVariable String connectionId, @RequestBody BulkCommandPreviewRequest request) {
    DsbulkOperation operation = DsbulkOperation.parse(request.operation());
    DsbulkJobSpec spec = new DsbulkJobSpec(
        operation,
        request.keyspace(),
        request.table(),
        request.query(),
        connectorFor(request.format()),
        url(operation, request.sink(), request.source()),
        null,
        false,
        null,
        10,
        DsbulkSettingsFlattener.flatten(request.dsbulkSettings()));

    DsbulkProbe probe = jobs.probe(connectionId, request.keyspace(), request.table());
    DsbulkPlan plan = DsbulkFactory.plan(spec, probe, null, null);

    return new BulkCommandPreview(
        plan.command(),
        plan.argv(),
        plan.hocon(),
        plan.maskedFields(),
        DerivedSetting.from(plan.settings()));
  }

  /**
   * Maps an export format onto a DSBulk connector.
   *
   * <p>DSBulk ships {@code csv} and {@code json} connectors and nothing else, so Parquet, XML and
   * XLSX are the native engine's job (plan section 5.2). Silently pretending otherwise would
   * generate a command DSBulk rejects at start-up.
   */
  static String connectorFor(String format) {
    if (format == null) {
      return "csv";
    }
    return switch (format.toUpperCase(java.util.Locale.ROOT)) {
      case "JSON", "JSONL" -> "json";
      default -> "csv";
    };
  }

  /** The connector URL: the sink for a read workflow, the source for a load. */
  static String url(DsbulkOperation operation, BulkSink sink, BulkSource source) {
    if (operation == DsbulkOperation.LOAD) {
      return source == null ? null : firstNonBlank(source.path(), source.s3Uri());
    }
    return sink == null ? null : firstNonBlank(sink.path(), sink.s3Uri());
  }

  static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /** Convenience for callers that already hold a flat override map. */
  static Map<String, String> emptyOverrides() {
    return Map.of();
  }

  /** The settings groups the contract models, in accordion order - used by the UI's group tabs. */
  static List<String> groups() {
    return List.of("connector", "schema", "batch", "codec", "engine", "executor", "log", "monitoring",
        "driver", "s3", "stats");
  }
}
