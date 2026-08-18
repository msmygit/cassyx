# cassyx backend — multi-stage build.
#
# Build context is ./backend (see docker-compose.yml), so every path here is relative to the
# reactor root. Plan section 2.2 requires `make up` to work with only Docker and Make installed,
# so the Maven build happens inside this image — never on the host.

# ---------------------------------------------------------------------------
# Stage 1 — build the multi-module reactor
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy only the POMs first so dependency resolution is cached independently of source changes.
# Without this split, editing one Java file re-downloads the entire dependency tree.
COPY pom.xml ./
COPY cassyx-core/pom.xml     cassyx-core/
COPY cassyx-bulk/pom.xml     cassyx-bulk/
COPY cassyx-vector/pom.xml   cassyx-vector/
COPY cassyx-migrate/pom.xml  cassyx-migrate/
COPY cassyx-license/pom.xml  cassyx-license/
COPY cassyx-api/pom.xml      cassyx-api/
COPY config/ config/

# `dependency:go-offline` is best-effort: it fails on some plugin-only artifacts, and a cold cache
# failure here must not fail the image build — the real build below resolves whatever is missing.
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY cassyx-core/src     cassyx-core/src
COPY cassyx-bulk/src     cassyx-bulk/src
COPY cassyx-vector/src   cassyx-vector/src
COPY cassyx-migrate/src  cassyx-migrate/src
COPY cassyx-license/src  cassyx-license/src
COPY cassyx-api/src      cassyx-api/src

# Tests are deliberately skipped in the image build. `make unit`, `make integration` and CI run
# them in a dedicated Maven container where a failure is reported as a test failure rather than as
# an opaque image-build error. Skipping here is what keeps `make up` fast.
RUN mvn -B -q package -DskipTests -Dcassyx.coverage.skip=true \
 && cp cassyx-api/target/cassyx-api-*.jar /build/cassyx-api.jar

# ---------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# curl is required by the compose healthcheck, which every other service waits on.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# DSBulk (plan section 5.3) — the BINARY DISTRIBUTION, not the executable jar.
#
# Three deliberate choices here:
#
#  1. The distribution (tar.gz), because upstream marks the single executable jar as
#     evaluation-only — not a licensing position to ship a product on. The distribution is also the
#     only form that carries bin/dsbulk and the full lib/ tree.
#  2. It is installed OUTSIDE the application's classpath and never added to it. DSBulk bundles an
#     application.conf, and Typesafe Config merges every application.conf on a classpath, so sharing
#     one with Spring Boot makes each side silently read the other's configuration. cassyx runs
#     DSBulk as a separate JVM process against /opt/dsbulk/lib instead — which also buys job
#     isolation, real cancellation, per-job memory caps, and immunity to DSBulk's System.exit().
#  3. The workflow jars are verified AT BUILD TIME. Workflows resolve through ServiceLoader, so a
#     missing one does not fail here — it fails at job time with DSBulk's own opaque
#     "first argument must be a subcommand". Better a red build than a red job.
#
# GitHub releases rather than downloads.datastax.com: the latter publishes only 1.11.0, while the
# pinned 1.11.1 (and 1.11.2) exist only as release assets.
ARG DSBULK_VERSION=1.11.1
ENV DSBULK_HOME=/opt/dsbulk
RUN set -eux; \
    curl -fsSL -o /tmp/dsbulk.tar.gz \
      "https://github.com/datastax/dsbulk/releases/download/${DSBULK_VERSION}/dsbulk-${DSBULK_VERSION}.tar.gz"; \
    mkdir -p /opt; \
    tar -xzf /tmp/dsbulk.tar.gz -C /opt; \
    mv "/opt/dsbulk-${DSBULK_VERSION}" "${DSBULK_HOME}"; \
    rm -f /tmp/dsbulk.tar.gz; \
    chmod +x "${DSBULK_HOME}/bin/dsbulk"; \
    for workflow in load unload count; do \
      ls "${DSBULK_HOME}/lib/dsbulk-workflow-${workflow}-"*.jar > /dev/null \
        || { echo "FATAL: dsbulk-workflow-${workflow} missing from the DSBulk distribution"; exit 1; }; \
    done

# Run as a non-root user. The UID is fixed so the bind-mounted ./.cassyx-out stays writable and
# does not end up root-owned on the host.
RUN groupadd --gid 10001 cassyx \
 && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin cassyx

WORKDIR /app
COPY --from=build /build/cassyx-api.jar /app/cassyx-api.jar

# CASSYX_DATA_DIR holds the H2 store (a named volume); /out receives job artifacts and export
# sinks, so bulk data lands on a mounted volume instead of round-tripping through the browser
# (plan section 2 "bulk data must never round-trip through the browser").
ENV CASSYX_DATA_DIR=/data \
    CASSYX_OUT_DIR=/out \
    CASSYX_PORT=8080 \
    JAVA_OPTS=""

# application.yml reads the H2 location from CASSYX_DB_PATH; derive it from CASSYX_DATA_DIR so
# callers only have to set the directory.
ENV CASSYX_DB_PATH=/data/cassyx

RUN mkdir -p /data /out && chown -R cassyx:cassyx /data /out /app
VOLUME ["/data", "/out"]

USER cassyx
EXPOSE 8080

# Matches the contract in `make show-contracts`: GET /api/health returns 200 once ready.
# start-period is generous because the first boot runs Flyway migrations against an empty H2 file.
HEALTHCHECK --interval=5s --timeout=5s --start-period=60s --retries=30 \
  CMD curl -fsS http://localhost:8080/api/health >/dev/null || exit 1

# `exec` so the JVM is PID 1 and receives SIGTERM directly — otherwise compose waits the full
# stop_grace_period on every `make down`, and in-flight bulk jobs are killed rather than cancelled.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/cassyx-api.jar"]
