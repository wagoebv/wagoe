# shop — production image
#
# Build:  docker build -t shop .
# Run:    docker run -p 3000:3000 -e JWT_SECRET=... -e WAG_ENV=prod shop
#
# Two stages so the runtime image carries a JRE and one jar, not the Clojure
# toolchain and a ~/.m2 cache.

# ── build ────────────────────────────────────────────────────────────────────
FROM clojure:temurin-21-tools-deps AS build
WORKDIR /app

# deps.edn alone first: this layer is cached until dependencies actually change,
# so ordinary source edits do not re-resolve the whole tree.
COPY deps.edn ./
RUN clojure -P -M:build

COPY . .
RUN clojure -T:build uber

# ── runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root: nothing here needs to write outside /app, and a container that
# cannot escalate is one less thing to reason about.
RUN addgroup -S app && adduser -S -G app app

COPY --from=build /app/target/*.jar /app/app.jar
RUN chown -R app:app /app
USER app

# WAG_ENV selects resources/conf/<env>/config.edn. `prod` expects a
# resources/conf/prod/config.edn, which `wagoe new` does not generate — add one
# before deploying, or the app will report the config as not found.
ENV WAG_ENV=prod
ENV HTTP_PORT=3000
EXPOSE 3000

# JWT_SECRET is deliberately not defaulted. A baked-in secret in an image is
# worse than a startup failure.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
