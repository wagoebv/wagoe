# Production Dockerfile for a Wagoe application.
#
# Multi-stage: build the uberjar, then ship it on a slim JRE as a non-root user.
# The same image runs either mode — the container arg selects it:
#   docker run <image>            # server (default)
#   docker run <image> worker     # background worker (no HTTP listener)
#
# For local development use resources/conf/dev/Dockerfile instead (dev tooling,
# wide port range, hot-reload stage).

# =============================================================================
# Build stage — produce target/wagoe-<version>-standalone.jar
# =============================================================================
FROM clojure:temurin-17-tools-deps AS builder

WORKDIR /app

# Dependency files first, so the dep-download layer is cached across source edits.
COPY deps.edn build.clj ./
COPY libs ./libs
RUN clojure -P && clojure -P -T:build

# Sources
COPY src ./src
COPY resources ./resources

RUN clojure -T:build uber

# =============================================================================
# Runtime stage — slim JRE, non-root, no build tooling
# =============================================================================
FROM eclipse-temurin:17-jre AS runtime

# curl only, for the container HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root runtime user.
RUN groupadd -g 1001 wagoe \
    && useradd -m -u 1001 -g wagoe -s /usr/sbin/nologin wagoe

WORKDIR /app

COPY --from=builder /app/target/*-standalone.jar ./app.jar
COPY resources/conf ./resources/conf
RUN mkdir -p logs && chown -R wagoe:wagoe /app

USER wagoe

# Container-aware heap sizing; override JAVA_OPTS to tune per deployment.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError" \
    WAG_ENV=prod \
    HTTP_PORT=3000 \
    HTTP_HOST=0.0.0.0

EXPOSE 3000

# Liveness/readiness are also wired as k8s probes (see deploy/k8s). The Docker
# HEALTHCHECK targets readiness so orchestrators that honour it don't route to a
# not-yet-ready instance. Only meaningful for the server; harmless for a worker.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${HTTP_PORT}/health/ready" || exit 1

# exec form so java is PID 1 (receives SIGTERM → graceful Jetty drain + ig/halt!).
# "$@" is the container arg: server (default) or worker.
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
CMD ["server"]
