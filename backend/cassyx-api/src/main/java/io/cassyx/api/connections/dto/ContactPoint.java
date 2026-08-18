package io.cassyx.api.connections.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** {@code host} plus an optional {@code port}, defaulting to the CQL port. */
public record ContactPoint(@NotBlank String host, @Min(1) @Max(65535) Integer port) {

  public static final int DEFAULT_PORT = 9042;

  public int portOrDefault() {
    return port == null || port <= 0 ? DEFAULT_PORT : port;
  }

  /** The {@code host:port} form the driver and the persistence layer both use. */
  public String toHostPort() {
    return host + ":" + portOrDefault();
  }

  /** Parses one {@code host:port} entry; a missing port means {@value #DEFAULT_PORT}. */
  public static ContactPoint parse(String hostPort) {
    String value = hostPort == null ? "" : hostPort.trim();
    int idx = value.lastIndexOf(':');
    if (idx < 0) {
      return new ContactPoint(value, DEFAULT_PORT);
    }
    try {
      return new ContactPoint(value.substring(0, idx), Integer.parseInt(value.substring(idx + 1)));
    } catch (NumberFormatException e) {
      return new ContactPoint(value, DEFAULT_PORT);
    }
  }
}
