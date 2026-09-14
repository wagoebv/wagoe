# Multi-stage Dockerfile for Wagoe Framework
# Optimized for development with port management integration

# =============================================================================
# Build Stage
# =============================================================================
FROM clojure:temurin-21-tools-deps AS builder

WORKDIR /app

# Copy dependency files first for better caching
COPY deps.edn .
COPY build.clj .

# Download dependencies (cached layer)
RUN clojure -P -X:dev:test:build

# Copy source code (including libs for monorepo build)
COPY src ./src
COPY resources ./resources
COPY test ./test
COPY libs ./libs

# Build application
ARG BUILD_TARGET=uber
RUN clojure -T:build ${BUILD_TARGET}

# =============================================================================
# Runtime Stage  
# =============================================================================
FROM eclipse-temurin:21-jre AS runtime

# Install useful utilities for development
RUN apt-get update && \
    apt-get install -y \
        curl \
        netcat-openbsd \
        bash \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Create non-root user for security (Debian/Ubuntu style)
RUN groupadd -g 1001 wagoe && \
    useradd -m -u 1001 -g wagoe -s /bin/bash wagoe

# Copy built application
COPY --from=builder /app/target/*.jar ./app.jar

# Copy configuration
COPY resources/conf/dev ./resources/conf/dev

# Create necessary directories
RUN mkdir -p logs && \
    chown -R wagoe:wagoe /app

# Switch to non-root user
USER wagoe

# Environment configuration
ENV JAVA_OPTS="-Xmx512m -Xms128m -XX:+UseG1GC -XX:+UseContainerSupport"
ENV PROFILE=dev
ENV HTTP_PORT=3000
ENV HTTP_HOST=0.0.0.0

# Expose port range for development
EXPOSE 3000-3010

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${HTTP_PORT:-3000}/health || exit 1

# Entry point with port management awareness
COPY --chown=wagoe:wagoe scripts/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
# A shell form, so $JAVA_OPTS above is expanded. `java` does not read that
# variable — JDK_JAVA_OPTIONS is the one it reads — so the exec form this
# replaces ran on the JVM's default heap while the ENV line, and the entrypoint's
# "JVM options:" log, both said otherwise (BOU-438).
#
# Same shape as the root Dockerfile: the timezone first so a deployment can
# still override it through JAVA_OPTS (BOU-431).
CMD ["sh", "-c", "exec java -Duser.timezone=UTC $JAVA_OPTS -jar app.jar"]

# =============================================================================
# Development Stage (Optional)
# =============================================================================  
FROM runtime AS development

# Switch back to root to install development tools
USER root

# Install development dependencies
RUN apt-get update && \
    apt-get install -y \
        git \
        vim \
        less \
        tree \
        htop \
    && rm -rf /var/lib/apt/lists/*

# Install Clojure CLI for REPL development
RUN curl -O https://download.clojure.org/install/linux-install-1.11.1.1413.sh && \
    chmod +x linux-install-1.11.1.1413.sh && \
    ./linux-install-1.11.1.1413.sh && \
    rm linux-install-1.11.1.1413.sh

# Copy source for development
COPY --chown=wagoe:wagoe src ./src
COPY --chown=wagoe:wagoe test ./test
COPY --chown=wagoe:wagoe deps.edn .
COPY --chown=wagoe:wagoe build.clj .

# Switch back to wagoe user
USER wagoe

# Enable live reload and development features
ENV DEV_MODE=true
ENV HOT_RELOAD=true

# Override entrypoint for development - keep container running
CMD ["tail", "-f", "/dev/null"]