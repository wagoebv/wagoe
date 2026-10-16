# HTTP helpers the app smokes share. Expect `fail` and `log_excerpt` defined
# and APP_PID set; wait_for_health sets PORT.

# Read the port the app reports rather than assuming one. The generated test
# profile carries no :wagoe/http block, so HTTP_PORT does not reach it there and
# the platform default applies — and the port manager may move off a taken port
# anyway. Both would show up as "the app did not start" if this hardcoded 3000.
# `:port` with the colon, deliberately: the port manager also logs the range it
# searched — "searching ports 3000-3099" — and a looser pattern picks 3000 out
# of that line even when the server ended up somewhere else. The last match
# wins, because the manager logs the requested port before the bound one.
wait_for_health() {
  local log=$1 tries=${2:-90}
  PORT=""
  for _ in $(seq 1 "$tries"); do
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

# The jwt-token POST /api/v1/auth/login answers for $2/$3 on base URL $1.
login_token() {
  curl -sS -X POST -H "Content-Type: application/json" \
       -d "{\"email\":\"$2\",\"password\":\"$3\"}" "$1/api/v1/auth/login" \
    | grep -oE '"jwt-token":"[^"]+"' | cut -d'"' -f4 || true
}
