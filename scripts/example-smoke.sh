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
. "$REPO_ROOT/scripts/lib/app-smoke.sh"

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

# The generated integration test boots the app through wagoe-test.
clojure -M:test --focus shop.system-test > /tmp/shop-test.log 2>&1 \
  || { log_excerpt /tmp/shop-test.log 30; fail "clojure -M:test --focus shop.system-test failed"; }
grep -qE '(^|[^0-9])1 tests,' /tmp/shop-test.log \
  || { log_excerpt /tmp/shop-test.log 30; fail "the generated system test did not run"; }
ok "the generated integration test passes through wagoe.test"

# The app runs :wagoe/authorization :enforce, so the product routes need a
# platform admin or product/read and /write. `bb create-admin` writes the admin
# from a process of its own, which cannot reach the test profile's in-memory
# H2, so these requests run on the dev profile's SQLite file. A shop-dev.db
# already there is put back afterwards.
DEV_DB=shop-dev.db
if [ -e "$DEV_DB" ]; then mv "$DEV_DB" "$DEV_DB.smoke-backup"; fi
# shellcheck disable=SC2317
restore_dev_db() {
  rm -f "$DEV_DB" "$DEV_DB-shm" "$DEV_DB-wal"
  if [ -e "$DEV_DB.smoke-backup" ]; then mv "$DEV_DB.smoke-backup" "$DEV_DB"; fi
}

WAG_ENV=dev clojure -M:run > /tmp/shop-smoke.log 2>&1 &
APP_PID=$!
# shellcheck disable=SC2317
cleanup() { kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; }
trap 'cleanup; restore_dev_db' EXIT

wait_for_health /tmp/shop-smoke.log
ok "boots on WAG_ENV=dev, on SQLite, with no external services"

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
TOKEN=$(login_token "http://localhost:$PORT" smoke@example.test Example-pass-1)
[ -n "$TOKEN" ] || fail "POST /api/v1/auth/login returned no jwt-token"
USER_AUTH=(-H "Authorization: Bearer $TOKEN")

# Signed in is not enough: in a new app nobody holds product/read until an
# administrator gives one of their groups a product role.
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${USER_AUTH[@]}" "http://localhost:$PORT/api/v1/products")
[ "$CODE" = "403" ] || fail "GET /api/v1/products without product/read answered $CODE, expected 403"
ok "under :enforce, a signed-in user without product/read is refused"

ADMIN_PW="Str0ng-Smoke-pass-9"
printf '%s\n%s\n' "$ADMIN_PW" "$ADMIN_PW" \
  | bb create-admin --env dev --email admin@example.test --name Admin > /tmp/shop-admin.log 2>&1 \
  || { log_excerpt /tmp/shop-admin.log 20; fail "bb create-admin failed"; }
grep -q "Admin user created successfully" /tmp/shop-admin.log \
  || { log_excerpt /tmp/shop-admin.log 20; fail "bb create-admin did not create the admin"; }
TOKEN=$(login_token "http://localhost:$PORT" admin@example.test "$ADMIN_PW")
[ -n "$TOKEN" ] || fail "POST /api/v1/auth/login returned no jwt-token for the admin"
AUTH=(-H "Authorization: Bearer $TOKEN")
ok "the first admin, made with bb create-admin, gets in"

# Everyone is the built-in group of every signed-in user. Given product/viewer,
# the user reads products and still may not write them.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" --data-urlencode "role=product/viewer" \
            "http://localhost:$PORT/web/admin/groups/00000000-0000-0000-0000-000000000001/roles")
[ "$CODE" = "303" ] || fail "giving Everyone product/viewer answered $CODE, expected 303"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${USER_AUTH[@]}" "http://localhost:$PORT/api/v1/products")
[ "$CODE" = "200" ] || fail "GET /api/v1/products with product/viewer answered $CODE, expected 200"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${USER_AUTH[@]}" -H "Content-Type: application/json" \
            -d '{"name":"Tee","sku":"T-0","price":9.99}' "http://localhost:$PORT/api/v1/products")
[ "$CODE" = "403" ] || fail "POST /api/v1/products with product/viewer answered $CODE, expected 403"
ok "a role on Everyone gives product/read, and not product/write"

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
# database, so this is the one assertion here that needs a schema, which
# :migrate-on-start? applies; without it the request answered 500 with
# `Table "products" not found` (BOU-484, BOU-485).
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

# The create form validates a field on the server when it changes, and the
# whole form again when it is posted without JavaScript.
FIELD=$(curl -fsS "${AUTH[@]}" -X POST -H "HX-Request: true" \
             --data-urlencode "__field=price" --data-urlencode "price=abc" \
             "http://localhost:$PORT/web/products/validate") \
  || { log_excerpt /tmp/shop-smoke.log 30; fail "POST /web/products/validate did not answer"; }
case "$FIELD" in
  *'id="price-field"'*"Must be a number"*) ;;
  *) fail "/web/products/validate returned '$FIELD', not the price field with its error" ;;
esac
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" -X POST \
            --data-urlencode "name=Mug" --data-urlencode "sku=M-1" "http://localhost:$PORT/web/products")
[ "$CODE" = "303" ] || fail "POST /web/products with a valid form answered $CODE, expected 303"
ok "the create form checks a field as it changes, and saves a valid form"

# The uberjar is how a generated project ships. On Linux this cannot catch the
# macOS LICENSE collision (BOU-549) — new_test.clj guards that — but it proves
# the template's build produces a jar that boots.
cleanup
clojure -T:build uber > /tmp/shop-uber.log 2>&1 \
  || { log_excerpt /tmp/shop-uber.log 30; fail "clojure -T:build uber failed"; }
java -jar target/shop-0.1.0.jar > /tmp/shop-jar.log 2>&1 &
APP_PID=$!
wait_for_health /tmp/shop-jar.log
ok "the uberjar builds and boots on WAG_ENV=test with no external services"

echo
echo "✅ examples/todo and examples/shop both run against this checkout"
