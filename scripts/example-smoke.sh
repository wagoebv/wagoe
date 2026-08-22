#!/usr/bin/env bash
# Smoke test for examples/ (BOU-300).
#
# examples/todo is pure core — it prints and exits. examples/shop is a
# generated application: it boots the framework and serves HTTP, so the check
# is a request, not a line of stdout.
#
# Both resolve com.wagoe through :local/root, so this tests the checkout rather
# than the last release. `bb example:regen --check` proves examples/shop is
# still what the generators produce; this script proves it runs.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() { echo; echo "EXAMPLE SMOKE FAILURE: $*"; exit 1; }
ok()   { echo "  ok — $*"; }

# ── examples/todo ───────────────────────────────────────────────────────────
echo "[1/2] examples/todo"
out=$(cd "$REPO_ROOT/examples/todo" && clojure -M:run)
echo "$out" | grep -q "3 todos, 2 remaining" \
  || { echo "$out"; fail "examples/todo output changed or the example failed to run"; }
ok "prints what its README says it prints"

# ── examples/shop ───────────────────────────────────────────────────────────
echo "[2/2] examples/shop"
cd "$REPO_ROOT/examples/shop"

# The test profile is H2 in-memory, so there is nothing to install and nothing
# to clean up. JWT_SECRET is normally in .env, which is generated per project
# and not committed.
export WAG_ENV=test
export JWT_SECRET="example-smoke-secret-minimum-32-characters"

clojure -M:run > /tmp/shop-smoke.log 2>&1 &
APP_PID=$!
# shellcheck disable=SC2317
cleanup() { kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; }
trap cleanup EXIT

# Read the port the app reports rather than assuming one. The generated test
# profile carries no :wagoe/http block, so HTTP_PORT does not reach it there and
# the platform default applies — and the port manager may move off a taken port
# anyway. Both would show up as "the app did not start" if this hardcoded 3000.
# `:port` with the colon, deliberately: the port manager also logs the range it
# searched — "searching ports 3000-3099" — and a looser pattern picks 3000 out
# of that line even when the server ended up somewhere else. The last match
# wins, because the manager logs the requested port before the bound one.
PORT=""
for _ in $(seq 1 90); do
  PORT=$(grep -oE ':port +[0-9]{4,5}' /tmp/shop-smoke.log 2>/dev/null \
           | grep -oE '[0-9]{4,5}' | tail -1 || true)
  if [ -n "$PORT" ] && curl -fsS -o /dev/null "http://localhost:$PORT/health" 2>/dev/null; then
    break
  fi
  kill -0 "$APP_PID" 2>/dev/null || { tail -30 /tmp/shop-smoke.log; fail "the app exited during startup"; }
  sleep 2
done
[ -n "$PORT" ] || { tail -30 /tmp/shop-smoke.log; fail "the app never reported a port"; }

curl -fsS -o /dev/null "http://localhost:$PORT/health" \
  || { tail -30 /tmp/shop-smoke.log; fail "/health never answered — the app did not start"; }
ok "boots on WAG_ENV=test with no external services"

# The point of the example: the scaffolded module serves a request. Asserting
# on the status alone would pass on the framework's 404 handler, which also
# returns a body — so assert on what the generated handler returns.
BODY=$(curl -fsS "http://localhost:$PORT/api/v1/products") \
  || { tail -30 /tmp/shop-smoke.log; fail "/api/v1/products did not answer"; }
[ "$BODY" = "[]" ] \
  || fail "/api/v1/products returned '$BODY', not the generated handler's []"
ok "the scaffolded module answers at /api/v1/products"

# The module mounts under the version prefix, so the unversioned path must
# redirect rather than 404. This is what catches a module that wrote /api into
# its own paths (Common Pitfalls #9).
CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/api/products")
[ "$CODE" = "307" ] \
  || fail "/api/products returned $CODE, expected a 307 to the versioned path"
ok "unversioned /api/products redirects to /api/v1"

echo
echo "✅ examples/todo and examples/shop both run against this checkout"
