package io.cassyx.api.bulk;

import io.cassyx.api.bulk.DsbulkDtos.BulkSource;
import io.cassyx.api.bulk.DsbulkDtos.BulkUpload;
import io.cassyx.api.bulk.DsbulkDtos.DsbulkJobView;
import io.cassyx.api.bulk.DsbulkDtos.LoadJobRequest;
import io.cassyx.bulk.api.dsbulk.DsbulkException;
import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Load jobs and their source uploads (plan section 5.3).
 *
 * <p>The upload endpoint streams straight to the server's staging area. Bulk data never round-trips
 * through the browser's memory in either direction - that is the single most important rule in the
 * architecture (plan section 2), and it applies to import exactly as much as to export.
 */
@RestController
public class LoadJobController {

  /** Staged uploads are reaped after this if no job ever references them. */
  static final Duration UPLOAD_TTL = Duration.ofHours(24);

  private final DsbulkJobService jobs;
  private final DsbulkTemplateRepository templates;
  private final Path uploadRoot;
  private final Clock clock;

  public LoadJobController(
      DsbulkJobService jobs, DsbulkTemplateRepository templates, Path dsbulkUploadRoot, Clock clock) {
    this.jobs = jobs;
    this.templates = templates;
    this.uploadRoot = dsbulkUploadRoot;
    this.clock = clock;
  }

  @PostMapping("/api/connections/{connectionId}/jobs/load")
  public ResponseEntity<DsbulkJobView> createLoadJob(
      @PathVariable String connectionId, @RequestBody LoadJobRequest request) {
    String url = resolveSource(request.source());
    Map<String, String> overrides = templates.merge(
        request.templateId(), DsbulkSettingsFlattener.flatten(request.dsbulkSettings()));

    DsbulkJobSpec spec = new DsbulkJobSpec(
        DsbulkOperation.LOAD,
        request.keyspace(),
        request.table(),
        null,
        DsbulkPlanningController.connectorFor(request.source() == null ? null : request.source().format()),
        url,
        request.mapping(),
        Boolean.TRUE.equals(request.dryRun()),
        null,
        10,
        overrides);

    DsbulkJobView job = jobs.submit(connectionId, request.name(), spec);
    return ResponseEntity.accepted().header("Location", "/api/jobs/" + job.id()).body(job);
  }

  @PostMapping("/api/bulk/uploads")
  public ResponseEntity<BulkUpload> uploadBulkSourceFile(
      @RequestParam("file") MultipartFile file, @RequestParam(value = "format", required = false) String format)
      throws IOException {
    String uploadId = "up_" + UUID.randomUUID().toString().replace("-", "");
    Path directory = uploadRoot.resolve(uploadId);
    Files.createDirectories(directory);
    Path target = directory.resolve(safeFileName(file.getOriginalFilename()));

    // Streamed, never buffered: a 100 GB import must not be a 100 GB heap allocation.
    try (InputStream in = file.getInputStream()) {
      Files.copy(in, target);
    }

    return ResponseEntity.status(HttpStatus.CREATED).body(new BulkUpload(
        uploadId,
        target.getFileName().toString(),
        Files.size(target),
        format,
        clock.instant().toString(),
        clock.instant().plus(UPLOAD_TTL).toString()));
  }

  /** Exactly one of the three source forms; an upload handle resolves to its staged path. */
  String resolveSource(BulkSource source) {
    if (source == null) {
      throw new DsbulkException("A load job needs a source: an uploadId, a server-side path, or an S3 URL.");
    }
    if (source.uploadId() != null && !source.uploadId().isBlank()) {
      Path directory = uploadRoot.resolve(safeFileName(source.uploadId()));
      if (!Files.isDirectory(directory)) {
        throw new DsbulkException("Unknown or expired upload '" + source.uploadId()
            + "'. Staged uploads are reaped after " + UPLOAD_TTL.toHours() + " hours; upload the file again.");
      }
      return directory.toString();
    }
    String direct = DsbulkPlanningController.firstNonBlank(source.path(), source.s3Uri());
    if (direct == null) {
      throw new DsbulkException("A load job needs a source: an uploadId, a server-side path, or an S3 URL.");
    }
    return direct;
  }

  /**
   * Strips every path separator and traversal segment.
   *
   * <p>An upload's file name is attacker-controlled: unsanitised, it is a write-anywhere primitive.
   */
  static String safeFileName(String name) {
    if (name == null || name.isBlank()) {
      return "upload.dat";
    }
    String base = name.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1);
    base = base.replaceAll("[^A-Za-z0-9._-]", "_");
    return base.isBlank() || ".".equals(base) || "..".equals(base) ? "upload.dat" : base;
  }
}
