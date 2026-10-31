#!/usr/bin/env bash
# Open every web page of a fresh app on one database engine (BOU-923).
#
#   scripts/web-crawl-smoke.sh sqlite|postgresql|mysql
#
# Generates an app from this checkout, signs in as its admin and runs
# scripts/web_crawl.clj: every page the web UI links to, each route again with
# odd parameters, any 4xx or 5xx fails. H2, which the e2e suite runs on,
# accepted the SQL that 1.1.0 then failed with on SQLite (BOU-919); this is
# the run on the engines apps actually use.
#
# postgresql reads POSTGRES_HOST/PORT/USER/PASSWORD, mysql MYSQL_HOST/PORT/
# USER/PASSWORD; each creates and drops a database of its own.
set -euo pipefail

ENGINE=${1:?usage: web-crawl-smoke.sh sqlite|postgresql|mysql}
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$REPO_ROOT/scripts/lib/log-excerpt.sh"
. "$REPO_ROOT/scripts/lib/smoke-worktree.sh"
. "$REPO_ROOT/scripts/lib/app-smoke.sh"

fail() { echo; echo "WEB CRAWL SMOKE FAILURE ($ENGINE): $*"; exit 1; }
ok()   { echo "  ok — $*"; }

export JWT_SECRET="web-crawl-smoke-secret-minimum-32-chars"
export WAG_ENV=dev
DB="wagoe_crawl_$$"
WORK=$(mktemp -d)
LOG="$WORK/app.log"
APP_PID=""
DB_CREATED=""

# Through the generated app's own driver, so no database client is needed
db_exec() {
  local spec
  case "$ENGINE" in
    postgresql) spec="{:dbtype \"postgresql\" :host \"$POSTGRES_HOST\" :port $POSTGRES_PORT :dbname \"postgres\" :user \"$POSTGRES_USER\" :password \"$POSTGRES_PASSWORD\"}" ;;
    mysql)      spec="{:dbtype \"mysql\" :host \"$MYSQL_HOST\" :port $MYSQL_PORT :user \"$MYSQL_USER\" :password \"$MYSQL_PASSWORD\"}" ;;
  esac
  (cd "$WORK/my-app" && clojure -M -e "(require 'next.jdbc) (next.jdbc/execute! (next.jdbc/get-datasource $spec) [\"$1\"])" >/dev/null)
}
cleanup() {
  [ -n "$APP_PID" ] && { kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; }
  [ -n "$DB_CREATED" ] && { db_exec "DROP DATABASE IF EXISTS $DB" || true; }
  rm -rf "$WORK"
}
trap cleanup EXIT

step() {
  local log=$1 msg=$2
  shift 2
  "$@" >>"$log" 2>&1 || { log_excerpt "$log" 20; fail "$msg"; }
}
wagoe() {
  clojure -Sdeps "{:deps {com.wagoe/wagoe-cli {:local/root \"$REPO_ROOT/libs/wagoe-cli\"}}}" \
    -M -m wagoe.cli.main "$@"
}

echo "[1/3] wagoe new on $ENGINE"
cd "$WORK"
step "$WORK/new.log" "wagoe new" wagoe new my-app --skip-git
pin_to_worktree "$WORK/my-app" "$REPO_ROOT"
cd "$WORK/my-app"
step "$WORK/setup.log" "bb setup --database $ENGINE" bb setup --database "$ENGINE" </dev/null
case "$ENGINE" in
  postgresql) export POSTGRES_HOST=${POSTGRES_HOST:-localhost} POSTGRES_PORT=${POSTGRES_PORT:-5432} \
                     POSTGRES_USER=${POSTGRES_USER:-postgres} POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-postgres} POSTGRES_DB=$DB
              db_exec "CREATE DATABASE $DB" || fail "could not create database $DB"; DB_CREATED=1 ;;
  mysql)      export MYSQL_HOST=${MYSQL_HOST:-127.0.0.1} MYSQL_PORT=${MYSQL_PORT:-3306} \
                     MYSQL_USER=${MYSQL_USER:-root} MYSQL_PASSWORD=${MYSQL_PASSWORD:-probe} MYSQL_DB=$DB
              db_exec "CREATE DATABASE $DB" || fail "could not create database $DB"; DB_CREATED=1 ;;
esac
ok "generated against this checkout"

echo "[2/3] boot, admin, users"
step "$WORK/migrate.log" "bb migrate up" bb migrate up </dev/null
PW='Crawl-pass-12345'
printf '%s\n%s\n' "$PW" "$PW" | step "$WORK/admin.log" "bb create-admin" \
  bb create-admin --email admin@crawl.test --name Admin
HTTP_PORT=3302 clojure -M:run >"$LOG" 2>&1 &
APP_PID=$!
wait_for_health "$LOG" 150
B="http://localhost:$PORT"
# Users through the create form, so lists, detail and rights pages have rows
JAR="$WORK/cookies"
curl -fsS -o /dev/null -c "$JAR" -d "email=admin@crawl.test" -d "password=$PW" -d remember=false "$B/web/login" \
  || fail "sign-in failed"
for n in ada bram chen daan; do
  curl -fsS -o /dev/null -b "$JAR" -d "name=$n" -d "email=$n@crawl.test" -d "password=Sample-pass-2026x" \
       -d role=user "$B/web/users" || { log_excerpt "$LOG" 30; fail "creating user $n failed"; }
done
ok "booted on $ENGINE with an admin and four users"

echo "[3/3] every page, and odd parameters"
bb "$REPO_ROOT/scripts/web_crawl.clj" "$B" admin@crawl.test "$PW" | tee "$WORK/crawl.log" \
  || { log_excerpt "$LOG" 40; fail "a web page answered 4xx or 5xx"; }
if grep -q " ERROR " "$LOG"; then
  grep " ERROR " "$LOG" | head -10
  fail "the crawl passed but the server logged errors"
fi
ok "no page failed and the server logged no error"
echo
echo "Web crawl smoke passed on $ENGINE."
