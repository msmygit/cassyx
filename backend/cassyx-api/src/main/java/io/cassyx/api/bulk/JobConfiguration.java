package io.cassyx.api.bulk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean wiring for the native job substrate (plan sections 5.2 and 5.5).
 *
 * <p>Spring lives only in {@code cassyx-api} (plan section 2.1, ArchUnit-enforced), so this is where
 * the engine's plain-Java collaborators get their lifecycle. The engine itself is built through
 * {@code BulkFactory} - the module's {@code ...api} entry point - and never from its implementation
 * package.
 */
@Configuration(proxyBeanMethods = false)
public class JobConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(JobConfiguration.class);

  /**
   * The bounded job executor of plan section 5.5.
   *
   * <p>Bounded rather than virtual-threaded, even though the engine's own consumers are virtual
   * threads. The unit of work here is a whole unload holding a token-range scan across the cluster;
   * "unbounded concurrency" at this level means an unbounded number of concurrent full-table scans
   * pointed at a production database. The cap is what the contract's {@code 429 JobCapExceeded}
   * response exists to report.
   *
   * <p>Named separately from {@code dsbulkJobExecutor} so the two engines cannot starve each other:
   * a queue of large DSBulk loads must not block an interactive export.
   */
  @Bean(destroyMethod = "shutdownNow")
  public ExecutorService nativeJobExecutor(
      @Value("${cassyx.jobs.max-concurrent:4}") int maxConcurrent) {
    int size = Math.max(1, maxConcurrent);
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            size,
            size,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
              Thread thread = new Thread(runnable, "cassyx-native-job");
              thread.setDaemon(true);
              return thread;
            });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  /**
   * Where {@code DOWNLOAD} artifacts are retained until the job is deleted.
   *
   * <p>Separate from the DSBulk work root: these files are served back to the browser, and mixing
   * them with a runner's scratch directory makes "what is safe to expose?" a question rather than an
   * invariant.
   */
  @Bean
  public Path nativeJobArtifactRoot(
      @Value("${cassyx.jobs.artifact-dir:./data/artifacts}") String artifactDir) {
    Path path = Path.of(artifactDir).toAbsolutePath().normalize();
    try {
      Files.createDirectories(path);
    } catch (IOException e) {
      LOG.warn("Cannot create the artifact directory {}: {}", path, e.toString());
    }
    return path;
  }
}
