---
name: wagoe-deploy
description: Package and deploy a Wagoe app — uberjar, Docker image, Kubernetes, systemd or a cloud manifest. Use when asked to build a release, containerise the app, deploy to an environment, or check a project is ready to ship. Covers the pre-deploy checks that actually catch things, the server/worker split, and the health endpoints to probe.
---

# Wagoe — deploying

## `bb deploy` is not this

```bash
bb deploy --all     # publishes 30 libraries to Clojars
```

It publishes the **framework's own libraries**. It has nothing to do with
deploying an application, and running it by mistake pushes artifacts to a
public repository, which cannot be undone. Application deployment is the
Dockerfile and the manifests below.

## Build

```bash
clojure -T:build clean && clojure -T:build uber   # ~40s, ~170 MB jar
java -jar target/wagoe-<version>-standalone.jar server
java -jar target/wagoe-<version>-standalone.jar worker
```

The same artifact runs either mode; the argument selects it. `worker` starts
the identical system minus the HTTP surface — no port is bound.

```bash
docker build -t wagoe:latest .        # multi-stage, ends on a slim JRE
docker run -p 3000:3000 -e JWT_SECRET=… -e WAG_ENV=prod wagoe:latest
docker run -e JWT_SECRET=… -e WAG_ENV=prod wagoe:latest worker
```

The image runs as the non-root user `wagoe`. Measured: ~3 minutes to build,
1.13 GB, health endpoint answering within 30 seconds of `docker run`.

Manifests, all examples to adapt rather than apply as-is:

| Target | File |
|---|---|
| Kubernetes | `deploy/k8s/wagoe.yaml` (server + worker Deployments) |
| systemd | `resources/deploy/systemd/wagoe.service` |
| nginx | `resources/deploy/nginx/wagoe.conf` |
| Fly.io / Render | `resources/deploy/cloud/{fly.toml,render.yaml}` |

## Before deploying

```bash
bb doctor --env prod --ci    # exits non-zero on error — usable as a gate
bb check                     # quality gates
bb test:all                  # every test surface
```

`bb doctor --env prod --ci` is the one that earns its place. It names the
environment variables the profile needs and prints the exports:

```
✗ env-refs   #env references without defaults are unset: ADMIN_EMAIL_DOMAIN,
             POSTGRES_DB, POSTGRES_HOST, POSTGRES_PASSWORD, POSTGRES_PORT,
             POSTGRES_USER, SENTRY_DSN, VERSION
```

Run it with the target environment's variables actually exported, not from a
clean shell — otherwise it reports everything as missing and tells you nothing.

**Migrations are a separate step.** Nothing in the container runs them; the app
boots against whatever schema it finds. Run `clojure -M:migrate up` (or
`bb migrate up`) against the target database before rolling out the new image,
and check `bb migrate status` afterwards.

## Health endpoints

All three answer 200 on a healthy server:

| Path | Use |
|---|---|
| `/health` | overall, with a JSON body naming service and version |
| `/health/live` | liveness probe — is the process up |
| `/health/ready` | readiness probe — should it receive traffic |

```json
{"status":"ok","service":"wagoe-test","version":"0.1.0","timestamp":"…"}
```

Shutdown is driven by `SIGTERM`: the server drains in-flight requests, halts
the system and exits 143. Keep `terminationGracePeriodSeconds` above
`HTTP_DRAIN_TIMEOUT_MS` (default 30s) or Kubernetes will kill it mid-drain.

## Production configuration that bites

- **`JWT_SECRET` must be at least 32 characters.** Shorter and the system
  refuses to build `:wagoe/auth-service`. It says so plainly now.
- **Rate limiting needs Redis in `:prod`.** With `HTTP_RATE_LIMIT_ENABLED=true`
  and no active `:wagoe/cache`, the limiter would count per process — an
  effective limit of `limit × replicas`, which is not a limit. The app fails at
  startup rather than offering that. Activate `:wagoe/cache` first.
- **`GET /metrics` is unauthenticated** when Prometheus is the metrics
  provider. That is the scrape convention, and it exposes internal counters and
  route cardinality — keep it off the public ingress and scrape the pod
  directly.
- **Heap is container-aware** (`-XX:MaxRAMPercentage=75`); tune via `JAVA_OPTS`.

## When a deploy fails

Read the one-line `Failed to start` from the container log:

```
ERROR wagoe.main - Failed to start — :wagoe/db-context could not be built:
FATAL: database "app" does not exist [org.postgresql.util.PSQLException]
```

It names the component and the real cause. Earlier versions logged the
exception object, which printed Integrant's whole `:value` map — including
`POSTGRES_PASSWORD`. If you are running an older build and see a config map in
a startup failure, treat the log as containing a secret and rotate it.

Most first deploys fail on one of: a missing environment variable (`bb doctor
--env prod --ci` would have said), migrations not run, or `JWT_SECRET` too
short.

## Steps

1. `bb doctor --env prod --ci` with the target environment's variables
   exported. Fix everything it names.
2. `bb check` and `bb test:all`.
3. Run migrations against the target database, and confirm with
   `bb migrate status`.
4. Build the image; run it locally against a throwaway database and curl
   `/health` before pushing it anywhere.
5. Deploy — server and worker from the same image.
6. Probe `/health/ready` on the deployed instance. A rollout that never becomes
   ready is a failed deploy however green the pipeline looked.

## What this does not cover

Secret management, registries, DNS and TLS are yours. Nothing here pushes an
image or touches a cluster — every command above is local, and the manifests
are examples to adapt.
