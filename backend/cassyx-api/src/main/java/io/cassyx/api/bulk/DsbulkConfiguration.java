package io.cassyx.api.bulk;

import io.cassyx.bulk.api.dsbulk.DsbulkDistribution;
import io.cassyx.bulk.api.dsbulk.DsbulkException;
import io.cassyx.bulk.api.dsbulk.DsbulkFactory;
import io.cassyx.bulk.api.dsbulk.DsbulkRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean wiring for the DSBulk integration. Every bean comes from {@code DsbulkFactory} - the module's
 * own {@code ...api} entry point - never from its implementation package (plan section 2.1,
 * ArchUnit-enforced).
 */
@Configuration(proxyBeanMethods = false)
public class DsbulkConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(DsbulkConfiguration.class);

  /**
   * The DSBulk distribution shipped in the image.
   *
   * <p>Verification is logged, NOT thrown. A missing bulk loader must not stop the application
   * booting: the schema browser, the query editor and the licence screen all work fine without it,
   * and an operator is far better served by a clear warning at start-up plus a clear error when
   * they actually request a DSBulk job than by a container that refuses to start.
   */
  @Bean
  public DsbulkDistribution dsbulkDistribution(@Value("${cassyx.dsbulk.home:}") String home) {
    DsbulkDistribution distribution =
        home == null || home.isBlank() ? DsbulkFactory.distribution() : DsbulkFactory.distribution(Path.of(home));
    try {
      distribution.verify();
      LOG.info("DSBulk distribution at {} with workflows {}", distribution.home(), distribution.workflows());
    } catch (DsbulkException e) {
      LOG.warn("DSBulk is not usable: {}", e.getMessage());
    }
    return distribution;
  }

  /**
   * @param maxHeap per-job heap cap. Capping the CHILD is the point: an unload that over-commits
   *     kills its own JVM instead of the API's.
   */
  @Bean
  public DsbulkRunner dsbulkRunner(
      DsbulkDistribution dsbulkDistribution, @Value("${cassyx.dsbulk.max-heap:2g}") String maxHeap) {
    return DsbulkFactory.runner(dsbulkDistribution, maxHeap);
  }

  /**
   * The bounded job executor of plan section 5.5.
   *
   * <p>Bounded, not virtual-threaded, and that is deliberate. Each task owns a whole child JVM with
   * its own heap; "unbounded concurrency" here means an unbounded number of multi-gigabyte
   * processes. The queue is where excess work waits, which is what the contract's {@code 429
   * JobCapExceeded} response is for.
   */
  @Bean(destroyMethod = "shutdownNow")
  public ExecutorService dsbulkJobExecutor(@Value("${cassyx.jobs.max-concurrent:4}") int maxConcurrent) {
    int size = Math.max(1, maxConcurrent);
    ThreadPoolExecutor executor = new ThreadPoolExecutor(
        size, size, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
        runnable -> {
          Thread thread = new Thread(runnable, "cassyx-dsbulk-job");
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  /** Per-job temp directories: the generated HOCON, the DSBulk log directory, the captured stdout. */
  @Bean
  public Path dsbulkJobWorkRoot(@Value("${cassyx.jobs.work-dir:./data/jobs}") String workDir) {
    return createDirectory(Path.of(workDir).toAbsolutePath().normalize());
  }

  /** Staging area for uploaded load sources. */
  @Bean
  public Path dsbulkUploadRoot(@Value("${cassyx.jobs.upload-dir:./data/uploads}") String uploadDir) {
    return createDirectory(Path.of(uploadDir).toAbsolutePath().normalize());
  }

  /** UTC and injected, so job timestamps do not depend on where the container runs, and are testable. */
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock cassyxClock() {
    return Clock.systemUTC();
  }

  private static Path createDirectory(Path path) {
    try {
      Files.createDirectories(path);
    } catch (IOException e) {
      LOG.warn("Cannot create the job directory {}: {}", path, e.toString());
    }
    return path;
  }
}
