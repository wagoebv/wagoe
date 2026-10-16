#!/usr/bin/env bash
# A generated application on MySQL (BOU-653).
#
# `wagoe new` + `bb scaffold` from this checkout: an invoice with an enum and a
# workflow, and lines that belong to an invoice. The app boots on MySQL with
# :migrate-on-start?, then the API, the transitions and the admin are driven
# over HTTP. MySQL support is claimed only as far as this run proves it.
#
# Needs a MySQL 8 server: MYSQL_HOST/PORT/USER/PASSWORD (default 127.0.0.1,
# 3306, root, probe — `bb test:services up`). MYSQL_DB is created and dropped.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$REPO_ROOT/scripts/lib/log-excerpt.sh"
. "$REPO_ROOT/scripts/lib/smoke-worktree.sh"

fail() { echo; echo "MYSQL SMOKE FAILURE: $*"; exit 1; }
ok()   { echo "  ok — $*"; }

export MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}" MYSQL_PORT="${MYSQL_PORT:-3306}"
export MYSQL_USER="${MYSQL_USER:-root}" MYSQL_PASSWORD="${MYSQL_PASSWORD:-probe}"
export MYSQL_DB="${MYSQL_DB:-wagoe_smoke_$$}"
export JWT_SECRET="mysql-smoke-secret-minimum-32-characters"
export WAG_ENV=dev
export WAGOE_SCAFFOLDER_ROOT="$REPO_ROOT/libs/scaffolder"

WORK=$(mktemp -d)
LOG="$WORK/app.log"
APP_PID=""
mysql_exec() {
  clojure -Sdeps '{:deps {com.mysql/mysql-connector-j {:mvn/version "26.7.0"} com.github.seancorfield/next.jdbc {:mvn/version "1.3.1118"}}}' \
    -M -e "(require '[next.jdbc :as j]) (j/execute! (j/get-datasource {:dbtype \"mysql\" :host \"$MYSQL_HOST\" :port $MYSQL_PORT :user \"$MYSQL_USER\" :password \"$MYSQL_PASSWORD\"}) [\"$1\"])" >/dev/null
}
# shellcheck disable=SC2317
cleanup() {
  [ -n "$APP_PID" ] && { kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; }
  mysql_exec "DROP DATABASE IF EXISTS $MYSQL_DB" || true
  rm -rf "$WORK"
}
trap cleanup EXIT

wagoe() {
  clojure -Sdeps "{:deps {com.wagoe/wagoe-cli {:local/root \"$REPO_ROOT/libs/wagoe-cli\"}}}" \
    -M -m wagoe.cli.main "$@"
}

# ── 1. generate ─────────────────────────────────────────────────────────────
echo "[1/5] wagoe new, MySQL, admin, workflow, events, scaffold"
cd "$WORK"
wagoe new smoke --skip-git >"$WORK/new.log" 2>&1 || { log_excerpt "$WORK/new.log" 20; fail "wagoe new"; }
pin_to_worktree "$WORK/smoke" "$REPO_ROOT"
cd "$WORK/smoke"
bb setup --database mysql --admin-ui true </dev/null >"$WORK/setup.log" 2>&1 \
  || { log_excerpt "$WORK/setup.log" 20; fail "bb setup --database mysql"; }
for m in workflow events; do
  wagoe add "$m" >"$WORK/add-$m.log" 2>&1 || { log_excerpt "$WORK/add-$m.log" 20; fail "wagoe add $m"; }
done
bb scaffold generate --module-name invoice --entity Invoice --field number:string:required \
  --field kind:enum:values=standard,credit:required:default=standard \
  --workflow 'status:draft>sent>paid' >"$WORK/scaffold.log" 2>&1 \
  || { log_excerpt "$WORK/scaffold.log" 20; fail "bb scaffold generate"; }
bb scaffold entity --module-name invoice --entity InvoiceLine --belongs-to invoice \
  --field qty:int:required >>"$WORK/scaffold.log" 2>&1 \
  || { log_excerpt "$WORK/scaffold.log" 20; fail "bb scaffold entity --belongs-to"; }
bb scaffold integrate invoice >>"$WORK/scaffold.log" 2>&1 \
  || { log_excerpt "$WORK/scaffold.log" 20; fail "bb scaffold integrate"; }
ok "generated against this checkout"

# ── 2. boot ─────────────────────────────────────────────────────────────────
echo "[2/5] boot on MySQL"
mysql_exec "CREATE DATABASE $MYSQL_DB"
HTTP_PORT=3301 clojure -M:run >"$LOG" 2>&1 &
APP_PID=$!
PORT=""
for _ in $(seq 1 120); do
  PORT=$(grep -oE ':port +[0-9]{4,5}' "$LOG" 2>/dev/null | grep -oE '[0-9]{4,5}' | tail -1 || true)
  [ -n "$PORT" ] && curl -fsS -o /dev/null "http://localhost:$PORT/health" 2>/dev/null && break
  kill -0 "$APP_PID" 2>/dev/null || { log_excerpt "$LOG" 40; fail "the app exited during startup"; }
  sleep 2
done
curl -fsS -o /dev/null "http://localhost:$PORT/health" || { log_excerpt "$LOG" 40; fail "/health never answered"; }
B="http://localhost:$PORT"
ok "booted; every migration applied on MySQL"

PW='Smoke-pass-12345'
printf '%s\n%s\n' "$PW" "$PW" | bb create-admin --env dev --email admin@smoke.test --name Admin \
  >"$WORK/admin.log" 2>&1 || { log_excerpt "$WORK/admin.log" 20; fail "bb create-admin"; }
grep -q "Admin user created successfully" "$WORK/admin.log" || { log_excerpt "$WORK/admin.log" 20; fail "no admin created"; }
TOKEN=$(curl -sS -X POST -H 'Content-Type: application/json' \
             -d "{\"email\":\"admin@smoke.test\",\"password\":\"$PW\"}" "$B/api/v1/auth/login" \
          | grep -oE '"jwt-token":"[^"]+"' | cut -d'"' -f4 || true)
[ -n "$TOKEN" ] || fail "login returned no jwt-token"
A=(-H "Authorization: Bearer $TOKEN")
J=(-H "Content-Type: application/json")

# ── 3. API ──────────────────────────────────────────────────────────────────
echo "[3/5] API: enum, belongs-to, timestamps"
CREATED=$(curl -sS -w '\n%{http_code}' -X POST "${A[@]}" "${J[@]}" -d '{"number":"I-1","kind":"credit"}' "$B/api/v1/invoices")
[ "$(echo "$CREATED" | tail -1)" = "201" ] || { log_excerpt "$LOG" 30; fail "create invoice: $CREATED"; }
ID=$(echo "$CREATED" | grep -oE '"id":"[0-9a-f-]{36}"' | grep -oE '[0-9a-f-]{36}' | head -1)
echo "$CREATED" | grep -q '"status":"draft"' || fail "a new invoice is not in draft: $CREATED"
# Zone-less, a MySQL DATETIME reads as local time though it is UTC (BOU-653).
echo "$CREATED" | grep -qE '"created-at":"[^"]+Z"' || fail "created-at carries no zone: $CREATED"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${A[@]}" "${J[@]}" -d '{"number":"I-9","kind":"bogus"}' "$B/api/v1/invoices")
[ "$CODE" = "400" ] || fail "an unknown enum value answered $CODE, expected 400"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${A[@]}" "${J[@]}" -d "{\"invoice-id\":\"$ID\",\"qty\":2}" "$B/api/v1/invoice-lines")
[ "$CODE" = "201" ] || fail "create invoice line answered $CODE"
curl -fsS "${A[@]}" "$B/api/v1/invoices/$ID?include=invoice-lines" | grep -q '"qty":2' \
  || fail "the invoice does not include its line"
ok "an enum is refused when unknown, a line belongs to its invoice, timestamps carry a zone"

# ── 4. transitions ──────────────────────────────────────────────────────────
echo "[4/5] workflow transitions"
SENT=$(curl -sS -w '\n%{http_code}' -X POST "${A[@]}" "${J[@]}" -d '{"transition":"sent"}' "$B/api/v1/invoices/$ID/transition")
[ "$(echo "$SENT" | tail -1)" = "200" ] && echo "$SENT" | grep -q '"status":"sent"' || fail "draft -> sent: $SENT"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${A[@]}" "${J[@]}" -d '{"transition":"draft"}' "$B/api/v1/invoices/$ID/transition")
[ "$CODE" = "422" ] || fail "sent -> draft answered $CODE, expected 422"
ok "draft -> sent moves, sent -> draft is refused"

# ── 5. admin ────────────────────────────────────────────────────────────────
echo "[5/5] admin CRUD"
curl -fsS -o "$WORK/create.html" "${A[@]}" --data-urlencode number=I-2 --data-urlencode kind=standard "$B/web/admin/invoices" \
  || fail "admin create failed"
grep -q "I-2" "$WORK/create.html" || fail "the admin list after create does not show I-2"
ID2=$(curl -fsS "${A[@]}" "$B/api/v1/invoices" | grep -oE '"id":"[0-9a-f-]{36}","number":"I-2"' | grep -oE '[0-9a-f-]{36}' || true)
[ -n "$ID2" ] || fail "the admin-created invoice is not in the API"
curl -fsS -o /dev/null -X PUT "${A[@]}" --data-urlencode number=I-2b --data-urlencode kind=credit "$B/web/admin/invoices/$ID2" \
  || fail "admin update failed"
curl -fsS "${A[@]}" "$B/api/v1/invoices/$ID2" | grep -q '"number":"I-2b"' || fail "admin update did not persist"
curl -fsS -o /dev/null -X DELETE "${A[@]}" "$B/web/admin/invoices/$ID2" || fail "admin delete failed"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" "$B/api/v1/invoices/$ID2")
[ "$CODE" = "404" ] || fail "a deleted invoice answered $CODE, expected 404"
ok "the admin creates, updates and deletes on MySQL"

echo
echo "MySQL smoke passed."
