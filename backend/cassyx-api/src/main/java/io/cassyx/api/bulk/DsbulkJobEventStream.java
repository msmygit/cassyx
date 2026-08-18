package io.cassyx.api.bulk;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The named-event SSE substrate for jobs (plan section 5.5, contract {@code /api/jobs/{id}/events}).
 *
 * <p>The contract specifies <b>named</b> events - {@code status}, {@code progress}, {@code log},
 * {@code completed}, {@code error} - not anonymous messages. That is load-bearing on the client
 * side: an {@code EventSource} with only an {@code onmessage} handler silently receives nothing
 * from a named stream, so a job would appear to hang forever with no error anywhere.
 *
 * <p>Every message carries a monotonically increasing id and is buffered, so a client that
 * reconnects with {@code Last-Event-ID} resumes without a gap rather than losing the progress it
 * missed. The buffer is bounded per job: a long unload emits thousands of log events and an
 * unbounded replay buffer is a memory leak with a delay fuse.
 *
 * <p><b>Wiring note.</b> This bean deliberately declares no {@code @GetMapping}. The
 * {@code /api/jobs/**} endpoints belong to the native-engine workstream; two controllers mapping one
 * path is a startup failure for the whole application. That workstream's job controller injects this
 * bean and calls {@link #subscribe}.
 */
@Component
public class DsbulkJobEventStream {

  private static final Logger LOG = LoggerFactory.getLogger(DsbulkJobEventStream.class);

  /** Retained events per job, for {@code Last-Event-ID} replay. */
  static final int REPLAY_BUFFER = 500;

  /** SSE stream lifetime. Longer than any sane job; the stream closes on completion regardless. */
  static final long TIMEOUT_MILLIS = 24L * 60 * 60 * 1000;

  /** One buffered event: its id, its NAME, and its already-serialised payload. */
  record Event(long id, String name, Object payload) {}

  private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
  private final Map<String, Deque<Event>> buffers = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();

  /**
   * Subscribes to a job's stream, replaying anything the client missed.
   *
   * @param lastEventId the client's {@code Last-Event-ID} header, or {@code null} for a fresh stream
   */
  public SseEmitter subscribe(String jobId, String lastEventId) {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
    subscribers.computeIfAbsent(jobId, key -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> remove(jobId, emitter));
    emitter.onTimeout(() -> remove(jobId, emitter));
    emitter.onError(error -> remove(jobId, emitter));

    long after = parseId(lastEventId);
    for (Event event : snapshot(jobId)) {
      if (event.id() > after) {
        send(emitter, event);
      }
    }
    return emitter;
  }

  /** Publishes a named event to every subscriber and to the replay buffer. */
  public void publish(String jobId, String name, Object payload) {
    Event event = new Event(sequence.incrementAndGet(), name, payload);
    Deque<Event> buffer = buffers.computeIfAbsent(jobId, key -> new ArrayDeque<>());
    synchronized (buffer) {
      buffer.addLast(event);
      while (buffer.size() > REPLAY_BUFFER) {
        buffer.removeFirst();
      }
    }
    for (SseEmitter emitter : subscribers.getOrDefault(jobId, List.of())) {
      send(emitter, event);
    }
  }

  /**
   * Publishes the terminal {@code completed} event and closes every stream.
   *
   * <p>The contract says {@code completed} is emitted exactly once and is the last frame before
   * close, so clients can treat it as end-of-stream and stop reconnecting.
   */
  public void complete(String jobId, Object payload) {
    publish(jobId, "completed", payload);
    for (SseEmitter emitter : subscribers.getOrDefault(jobId, List.of())) {
      try {
        emitter.complete();
      } catch (RuntimeException e) {
        LOG.debug("Closing the SSE stream for job {} failed: {}", jobId, e.toString());
      }
    }
    subscribers.remove(jobId);
  }

  /** Drops a finished job's replay buffer. Called when the job row is deleted. */
  public void forget(String jobId) {
    buffers.remove(jobId);
    subscribers.remove(jobId);
  }

  /** Buffered events, oldest first. Visible for testing. */
  List<Event> snapshot(String jobId) {
    Deque<Event> buffer = buffers.get(jobId);
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      return List.copyOf(buffer);
    }
  }

  int subscriberCount(String jobId) {
    return subscribers.getOrDefault(jobId, List.of()).size();
  }

  private void send(SseEmitter emitter, Event event) {
    try {
      emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.name()).data(event.payload()));
    } catch (IOException | IllegalStateException e) {
      // A disconnected browser is the normal case, not an error worth a stack trace.
      LOG.debug("SSE subscriber went away: {}", e.toString());
    }
  }

  private void remove(String jobId, SseEmitter emitter) {
    List<SseEmitter> emitters = subscribers.get(jobId);
    if (emitters != null) {
      emitters.remove(emitter);
    }
  }

  static long parseId(String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) {
      return 0;
    }
    try {
      return Long.parseLong(lastEventId.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
