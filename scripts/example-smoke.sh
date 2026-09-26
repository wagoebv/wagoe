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
. "$REPO_ROOT/scripts/lib/log-excerpt.sh"

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
wait_for_health() {
  local log=$1
  PORT=""
  for _ in $(seq 1 90); do
    PORT=$(grep -oE ':port +[0-9]{4,5}' "$log" 2>/dev/null \
             | grep -oE '[0-9]{4,5}' | tail -1 || true)
    if [ -n "$PORT" ] && curl -fsS -o /dev/null "http://localhost:$PORT/health" 2>/dev/null; then
      break
    fi
    kill -0 "$APP_PID" 2>/dev/null || { log_excerpt "$log" 30; fail "the app exited during startup"; }
    sleep 2
  done
  [ -n "$PORT" ] || { log_excerpt "$log" 30; fail "the app never reported a port"; }

  curl -fsS -o /dev/null "http://localhost:$PORT/health" \
    || { log_excerpt "$log" 30; fail "/health never answered — the app did not start"; }
}
wait_for_health /tmp/shop-smoke.log
ok "boots on WAG_ENV=test with no external services"

# Generated APIs require a signed-in user (BOU-539). Without one, a write is
# refused before it reaches the handler.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Content-Type: application/json" \
            -d '{"name":"Tee","sku":"T-0","price":9.99}' "http://localhost:$PORT/api/v1/products")
[ "$CODE" = "401" ] || fail "POST /api/v1/products without signing in answered $CODE, expected 401"
ok "the scaffolded API refuses a request without a signed-in user"

# Sign up through the web form, then log in over the API for a token. The
# session cookie the form sets is Secure, which curl keeps off plain HTTP.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
            --data-urlencode "name=Smoke" --data-urlencode "email=smoke@example.test" \
            --data-urlencode "password=Example-pass-1" "http://localhost:$PORT/web/register")
[ "$CODE" = "303" ] || { log_excerpt /tmp/shop-smoke.log 30; fail "POST /web/register answered $CODE, expected 303"; }
LOGIN=$(curl -sS -X POST -H "Content-Type: application/json" \
             -d '{"email":"smoke@example.test","password":"Example-pass-1"}' \
             "http://localhost:$PORT/api/v1/auth/login")
TOKEN=$(echo "$LOGIN" | grep -oE '"jwt-token":"[^"]+"' | cut -d'"' -f4 || true)
[ -n "$TOKEN" ] || fail "POST /api/v1/auth/login returned no jwt-token: '$LOGIN'"
AUTH=(-H "Authorization: Bearer $TOKEN")

# The point of the example: the scaffolded module serves a request. Asserting
# on the status alone would pass on the framework's 404 handler, which also
# returns a body — so assert on what the generated handler returns.
BODY=$(curl -fsS "${AUTH[@]}" "http://localhost:$PORT/api/v1/products") \
  || { log_excerpt /tmp/shop-smoke.log 30; fail "/api/v1/products did not answer"; }
[ "$BODY" = "[]" ] \
  || fail "/api/v1/products returned '$BODY', not the generated handler's []"
ok "the scaffolded module answers at /api/v1/products"

# The handlers reach the service: a POST writes a row the next GET reads. They
# were stubs that answered 201 and {} and wrote nothing (BOU-539).
CREATED=$(curl -sS -w '\n%{http_code}' -X POST "${AUTH[@]}" -H "Content-Type: application/json" \
               -d '{"name":"Tee","sku":"T-1","price":9.99}' \
               "http://localhost:$PORT/api/v1/products")
[ "$(echo "$CREATED" | tail -1)" = "201" ] \
  || { log_excerpt /tmp/shop-smoke.log 30; fail "POST /api/v1/products answered '$CREATED', expected 201"; }
ID=$(echo "$CREATED" | grep -oE '"id" *: *"[0-9a-f-]{36}"' | grep -oE '[0-9a-f-]{36}' || true)
[ -n "$ID" ] || fail "POST /api/v1/products returned no id: '$CREATED'"
GOT=$(curl -fsS "${AUTH[@]}" "http://localhost:$PORT/api/v1/products/$ID") \
  || fail "GET /api/v1/products/$ID did not answer"
case "$GOT" in
  *'"sku":"T-1"'*) ;;
  *) fail "GET /api/v1/products/$ID returned '$GOT', not the row just created" ;;
esac
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" -H "Content-Type: application/json" \
            -d '{"name":"Tee"}' "http://localhost:$PORT/api/v1/products")
[ "$CODE" = "400" ] || fail "POST /api/v1/products without its required fields answered $CODE, expected 400"
ok "POST /api/v1/products writes a row that GET reads back, and a bad body is a 400"

# The module mounts under the version prefix, so the unversioned path must
# redirect rather than 404. This is what catches a module that wrote /api into
# its own paths (Common Pitfalls #9).
CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/api/products")
[ "$CODE" = "307" ] \
  || fail "/api/products returned $CODE, expected a 307 to the versioned path"
ok "unversioned /api/products redirects to /api/v1"

# The web route renders core/ui.clj from rows the service read out of the
# database, so this is the one assertion here that needs a schema. It could not
# be made until :migrate-on-start? existed: the test profile is in-memory H2
# inside this process, so no separate `clojure -M:migrate up` can reach it, and
# the request answered 500 with `Table "products" not found` (BOU-484, BOU-485).
# Signed out, the page sends the browser to the login page (BOU-539).
LOC=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "http://localhost:$PORT/web/products")
case "$LOC" in
  "302 "*"/web/login?return-to=%2Fweb%2Fproducts") ;;
  *) fail "/web/products signed out answered '$LOC', expected a 302 to /web/login" ;;
esac
WEB=$(curl -fsS "${AUTH[@]}" "http://localhost:$PORT/web/products") \
  || { log_excerpt /tmp/shop-smoke.log 30; fail "/web/products did not answer"; }
case "$WEB" in
  *"<h1>Products</h1>"*"T-1"*) ;;
  *) fail "/web/products returned '$WEB', not the generated page with the row created above" ;;
esac
ok "the web page redirects a signed-out visitor, and shows a signed-in one the rows"

# The uberjar is how a generated project ships. On Linux this cannot catch the
# macOS LICENSE collision (BOU-549) — new_test.clj guards that — but it proves
# the template's build produces a jar that boots.
cleanup
clojure -T:build uber > /tmp/shop-uber.log 2>&1 \
  || { log_excerpt /tmp/shop-uber.log 30; fail "clojure -T:build uber failed"; }
java -jar target/shop-0.1.0.jar > /tmp/shop-jar.log 2>&1 &
APP_PID=$!
wait_for_health /tmp/shop-jar.log
ok "the uberjar builds and boots"

echo
echo "✅ examples/todo and examples/shop both run against this checkout"
