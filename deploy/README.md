# Deploying Wagoe

Production packaging for a Wagoe application: a prod Docker image, reference
Docker Compose topologies, and Kubernetes manifests.

| What | Where |
|---|---|
| N replicas + Redis + load balancer | [`compose/multi-instance.yml`](compose/multi-instance.yml) |
| One module as its own service | [`compose/per-service.yml`](compose/per-service.yml) |
| Kubernetes (server, worker, Redis, Ingress, module-as-a-service) | [`k8s/wagoe.yaml`](k8s/wagoe.yaml) |

The reasoning, the environment variables and what has actually been brought up
locally: **[Deployment Topologies](../docs/modules/architecture/pages/deployment-topologies.adoc)**.

## Build the image

The root [`Dockerfile`](../Dockerfile) is a multi-stage build (uberjar → slim
JRE, non-root):

```bash
docker build -t wagoe:latest .
```

## Run it

The same image runs either mode — the container arg selects it:

```bash
docker run -p 3000:3000 \
  -e JWT_SECRET=... -e WAG_ENV=prod \
  wagoe:latest              # server (HTTP, default)

docker run \
  -e JWT_SECRET=... -e WAG_ENV=prod \
  wagoe:latest worker       # background worker (no HTTP listener)
```

- **server** — the HTTP application (Jetty). Exposes `/health`, `/health/live`,
  `/health/ready`; drains in-flight requests on `SIGTERM`.
- **worker** — the same system with the HTTP surface removed. Runs background
  components (jobs, scheduled tasks, realtime) and no port is bound. This is the
  counterpart to `server` that makes the web/worker split in
  `docs/modules/architecture/pages/scaling.adoc` achievable.

## Kubernetes

[`k8s/wagoe.yaml`](k8s/wagoe.yaml) is a complete example: a `server`
Deployment behind a Service (with liveness/readiness probes on the health
endpoints and a preStop drain), plus a `worker` Deployment running the same
image with `args: ["worker"]`.

```bash
# Set REGISTRY/wagoe:TAG and the Secret values first.
kubectl apply -f deploy/k8s/wagoe.yaml
```

## Production configuration notes

- **JWT_SECRET is required** (min 32 chars) — the app refuses to boot without it.
- **Rate limiting requires Redis in `:prod`.** With rate limiting enabled and no
  active `:wagoe/cache`, the limiter would fall back to a per-process counter
  (effective limit = `limit × replicas`, i.e. not a real limit). In the `:prod`
  profile the app **fails loudly at startup** in that case rather than offering
  false protection. Activate `:wagoe/cache` (Redis) before setting
  `HTTP_RATE_LIMIT_ENABLED=true`.
- **Graceful shutdown** is driven by `SIGTERM`; keep the pod's
  `terminationGracePeriodSeconds` above `HTTP_DRAIN_TIMEOUT_MS` (default 30s) for
  zero-downtime rollouts.
- **Heap** is container-aware by default (`-XX:MaxRAMPercentage=75`); override
  `JAVA_OPTS` to tune.
- **`GET /metrics` is unauthenticated.** With `:wagoe/metrics {:provider
  :prometheus}` the app serves a Prometheus scrape endpoint at `/metrics` with no
  auth (the scrape convention). It exposes internal counters and route
  cardinality, so **do not route it through your public ingress** — restrict it
  to the cluster/monitoring network (e.g. a k8s `NetworkPolicy`, or scrape the
  pod IP directly and keep `/metrics` off the Service that backs the ingress).
