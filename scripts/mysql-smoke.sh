#!/usr/bin/env bash
# A generated application on MySQL (BOU-653).
#
# `wagoe new` + `bb scaffold` from this checkout: an invoice with an enum and a
# workflow, and lines that belong to an invoice. The app boots on MySQL with
# :migrate-on-start?, then the API, the transitions and the admin are driven
# over HTTP. MySQL support is claimed only as far as this run proves it.
#
# Needs a MySQL 8 server: MYSQL_HOST/PORT/USER/PASSWORD, defaulting to what
# `bb test:services up` starts. MYSQL_DB must not exist; it is created and dropped.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$REPO_ROOT/scripts/lib/log-excerpt.sh"
. "$REPO_ROOT/scripts/lib/smoke-worktree.sh"
. "$REPO_ROOT/scripts/lib/app-smoke.sh"

fail() { echo; echo "MYSQL SMOKE FAILURE: $*"; exit 1; }
ok()   { echo "  ok — $*"; }

export MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}" MYSQL_PORT="${MYSQL_PORT:-${WAGOE_TEST_MYSQL_PORT:-3306}}"
export MYSQL_USER="${MYSQL_USER:-root}" MYSQL_PASSWORD="${MYSQL_PASSWORD:-${WAGOE_TEST_MYSQL_PASSWORD:-probe}}"
export MYSQL_DB="${MYSQL_DB:-wagoe_smoke_$$}"
export JWT_SECRET="mysql-smoke-secret-minimum-32-characters"
export WAG_ENV=dev
export WAGOE_SCAFFOLDER_ROOT="$REPO_ROOT/libs/scaffolder"

WORK=$(mktemp -d)
LOG="$WORK/app.log"
APP_PID=""
DB_CREATED=""
# The mysql client where there is one (CI runners have it); else a JVM, on the
# driver versions the repo pins.
mysql_exec() {
  if command -v mysql >/dev/null; then
    MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -e "$1"
  else
    local v
    v=$(grep -oE '(com\.mysql/mysql-connector-j|com\.github\.seancorfield/next\.jdbc) +\{:mvn/version "[^"]+"\}' "$REPO_ROOT/deps.edn" | sort -u | tr '\n' ' ')
    (cd "$WORK" && clojure -Sdeps "{:deps {$v}}" -M -e "(require '[next.jdbc :as j]) (j/execute! (j/get-datasource {:dbtype \"mysql\" :host \"$MYSQL_HOST\" :port $MYSQL_PORT :user \"$MYSQL_USER\" :password \"$MYSQL_PASSWORD\"}) [\"$1\"])" >/dev/null)
  fi
}
# Run "$@" with its output in $1; on failure show it and stop with $2.
step() {
  local log=$1 msg=$2
  shift 2
  "$@" >>"$log" 2>&1 || { log_excerpt "$log" 20; fail "$msg"; }
}
# shellcheck disable=SC2317
cleanup() {
  [ -n "$APP_PID" ] && { kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; }
  # Only the database this run created: MYSQL_DB may name someone's own.
  [ -n "$DB_CREATED" ] && { mysql_exec "DROP DATABASE IF EXISTS $MYSQL_DB" || true; }
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
step "$WORK/new.log" "wagoe new" wagoe new smoke --skip-git
pin_to_worktree "$WORK/smoke" "$REPO_ROOT"
cd "$WORK/smoke"
step "$WORK/setup.log" "bb setup --database mysql" bb setup --database mysql --admin-ui true </dev/null
step "$WORK/add.log" "wagoe add workflow" wagoe add workflow
step "$WORK/add.log" "wagoe add events" wagoe add events
step "$WORK/scaffold.log" "bb scaffold generate" bb scaffold generate --module-name invoice --entity Invoice \
  --field number:string:required --field kind:enum:values=standard,credit:required:default=standard \
  --workflow 'status:draft>sent>paid'
step "$WORK/scaffold.log" "bb scaffold entity --belongs-to" bb scaffold entity --module-name invoice \
  --entity InvoiceLine --belongs-to invoice --field qty:int:required
step "$WORK/scaffold.log" "bb scaffold integrate" bb scaffold integrate invoice
ok "generated against this checkout"

# ── 2. boot ─────────────────────────────────────────────────────────────────
echo "[2/5] boot on MySQL"
mysql_exec "CREATE DATABASE $MYSQL_DB" || fail "could not create database $MYSQL_DB — does it exist already?"
DB_CREATED=1
HTTP_PORT=3301 clojure -M:run >"$LOG" 2>&1 &
APP_PID=$!
wait_for_health "$LOG" 120
B="http://localhost:$PORT"
ok "booted; every migration applied on MySQL"

PW='Smoke-pass-12345'
printf '%s\n%s\n' "$PW" "$PW" | step "$WORK/admin.log" "bb create-admin" \
  bb create-admin --env dev --email admin@smoke.test --name Admin
grep -q "Admin user created successfully" "$WORK/admin.log" || { log_excerpt "$WORK/admin.log" 20; fail "no admin created"; }
TOKEN=$(login_token "$B" admin@smoke.test "$PW")
[ -n "$TOKEN" ] || fail "login returned no jwt-token"
A=(-H "Authorization: Bearer $TOKEN")
J=(-H "Content-Type: application/json")

# ── 3. API ──────────────────────────────────────────────────────────────────
echo "[3/5] API: enum, belongs-to, timestamps"
CREATED=$(curl -sS -w '\n%{http_code}' -X POST "${A[@]}" "${J[@]}" -d '{"number":"I-1","kind":"credit"}' "$B/api/v1/invoices")
[ "$(echo "$CREATED" | tail -1)" = "201" ] || { log_excerpt "$LOG" 30; fail "create invoice: $CREATED"; }
ID=$(echo "$CREATED" | grep -oE '"id":"[0-9a-f-]{36}"' | grep -oE '[0-9a-f-]{36}' | head -1 || true)
[ -n "$ID" ] || fail "the created invoice has no id: $CREATED"
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
