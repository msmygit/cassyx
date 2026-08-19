# cassyx licensing service - multi-stage build.
#
# IMPORTANT: the build context is the REPOSITORY ROOT, not licensing/, because this module builds
# against backend/cassyx-license (it reuses the licence payload model and the verifier so a minted
# key is checked with the very code the product runs):
#
#   docker build -f licensing/Dockerfile -t cassyx-licensing .
#
# This image is NEVER shipped to customers. It is the only place the Ed25519 private key exists.

# ---------------------------------------------------------------------------
# Stage 1 - build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first so dependency resolution caches independently of source changes.
COPY backend/pom.xml                backend/pom.xml
COPY backend/cassyx-license/pom.xml backend/cassyx-license/pom.xml
COPY backend/config/                backend/config/
COPY licensing/pom.xml              licensing/pom.xml
RUN mvn -B -q -f backend/pom.xml -Plicensing -pl ../licensing -am dependency:go-offline -DskipTests || true

COPY backend/cassyx-license/src backend/cassyx-license/src
COPY licensing/src              licensing/src

# Tests run in CI, not in the image build - a failure here would surface as an opaque build error.
RUN mvn -B -q -f backend/pom.xml -Plicensing -pl ../licensing -am package -DskipTests \
 && cp licensing/target/cassyx-licensing-*.jar /build/cassyx-licensing.jar

# ---------------------------------------------------------------------------
# Stage 2 - runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10002 licensing \
 && useradd --uid 10002 --gid 10002 --create-home --shell /usr/sbin/nologin licensing

WORKDIR /app
COPY --from=build /build/cassyx-licensing.jar /app/cassyx-licensing.jar

# The H2 file is the ONLY record of which payment produced which key, so it must be on a volume.
# Losing it makes every future "email me my key again" request unanswerable.
ENV CASSYX_LICENSING_DB_PATH=/data/licensing \
    CASSYX_LICENSING_PORT=8090 \
    JAVA_OPTS=""

RUN mkdir -p /data && chown -R licensing:licensing /data /app
VOLUME ["/data"]

USER licensing
EXPOSE 8090

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=10 \
  CMD curl -fsS http://localhost:8090/licensing/health >/dev/null || exit 1

# exec so the JVM is PID 1 and receives SIGTERM directly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/cassyx-licensing.jar"]
