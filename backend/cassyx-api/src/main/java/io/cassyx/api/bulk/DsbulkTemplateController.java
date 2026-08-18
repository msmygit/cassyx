package io.cassyx.api.bulk;

import io.cassyx.api.bulk.DsbulkDtos.JobTemplate;
import io.cassyx.api.bulk.DsbulkDtos.JobTemplateRequest;
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

/** CRUD over reusable DSBulk job templates ({@code /api/job-templates}, plan section 5.3). */
@RestController
public class DsbulkTemplateController {

  private final DsbulkTemplateRepository templates;

  public DsbulkTemplateController(DsbulkTemplateRepository templates) {
    this.templates = templates;
  }

  @GetMapping("/api/job-templates")
  public List<JobTemplate> listJobTemplates(
      @RequestParam(value = "operation", required = false) String operation) {
    return templates.list(operation);
  }

  @PostMapping("/api/job-templates")
  public ResponseEntity<JobTemplate> createJobTemplate(@RequestBody JobTemplateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(templates.create(request));
  }

  @GetMapping("/api/job-templates/{templateId}")
  public ResponseEntity<JobTemplate> getJobTemplate(@PathVariable String templateId) {
    return templates.find(templateId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PutMapping("/api/job-templates/{templateId}")
  public ResponseEntity<JobTemplate> updateJobTemplate(
      @PathVariable String templateId, @RequestBody JobTemplateRequest request) {
    return templates.update(templateId, request)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @DeleteMapping("/api/job-templates/{templateId}")
  public ResponseEntity<Void> deleteJobTemplate(@PathVariable String templateId) {
    return templates.delete(templateId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }
}
