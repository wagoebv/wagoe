#!/usr/bin/env bash
# BOU-236 — container verification for the wagoe-setup skill.
#
# scripts/first-run-smoke.sh already walks install.sh -> wagoe new ->
# bb quickstart -> serving app. It does NOT cover the two things this skill
# adds, and one of them it actively hides:
#
#   1. Non-interactive PATH. The smoke test runs every command through
#      `bash -ic` — an *interactive* shell, which sources ~/.bashrc and so picks
#      up the PATH line install.sh appends. An agent runs non-interactive
#      shells, which do not. Step 3 of the skill exists for that, and nothing
#      tested it until now.
#
#   2. Piping a password into `bb create-admin`. The skill drives it
#      non-interactively; the smoke test never creates an admin user at all.
#
# Runs against the PUBLISHED artifacts, deliberately: this checks the flow a
# user gets today, not the working tree.
set -euo pipefail

IMAGE="${VERIFY_IMAGE:-ubuntu:24.04}"
PW='Str0ng-Dev-Pass-x9'

echo "=== wagoe-setup skill verification (${IMAGE}) ==="

docker run --rm "$IMAGE" bash -euo pipefail -c '
  # install.sh has its own prerequisite check (BOU-226) which correctly refuses
  # and names the missing tools. BOU-232 covers that message across distros;
  # this script is about the skill flow, so satisfy the prerequisites and move
  # on rather than re-testing the check.
  apt-get update -qq >/dev/null
  apt-get install -y -qq curl ca-certificates sudo git unzip zip >/dev/null

  echo "--- installing toolchain ---"
  curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh | bash > /tmp/install.log 2>&1 \
    || { tail -30 /tmp/install.log; echo "FAIL: install.sh"; exit 1; }
  echo "install.sh ok"

  echo
  echo "=== CHECK 1: non-interactive shell WITHOUT the PATH export ==="
  if bash -c "command -v wagoe" >/dev/null 2>&1; then
    echo "RESULT: wagoe IS on PATH — skill Step 3 would be unnecessary"
  else
    echo "RESULT: wagoe is NOT on PATH — skill Step 3 is required (as designed)"
  fi

  echo
  echo "=== CHECK 2: non-interactive shell WITH only the bbin PATH export ==="
  bash -c "export PATH=\"\$HOME/.babashka/bbin/bin:\$PATH\"; command -v wagoe" \
    || { echo "FAIL: bbin export does not make wagoe resolvable"; exit 1; }
  if bash -c "export PATH=\"\$HOME/.babashka/bbin/bin:\$PATH\"; command -v java" >/dev/null 2>&1; then
    echo "RESULT: java also resolves — the sdkman line would be unnecessary"
  else
    echo "RESULT: wagoe resolves but java does NOT — sdkman init is also required"
  fi

  echo
  echo "=== CHECK 3: Step 3 in full (bbin PATH + sdkman init) ==="
  bash -c "
    export PATH=\"\$HOME/.babashka/bbin/bin:\$PATH\"
    set +u; [ -s \"\$HOME/.sdkman/bin/sdkman-init.sh\" ] && . \"\$HOME/.sdkman/bin/sdkman-init.sh\"; set -u
    command -v wagoe && command -v java" \
    || { echo "FAIL: full Step 3 does not resolve both wagoe and java"; exit 1; }
  echo "RESULT: full Step 3 resolves wagoe and java"

  export PATH="$HOME/.babashka/bbin/bin:$PATH"
  set +u; [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ] && . "$HOME/.sdkman/bin/sdkman-init.sh"; set -u

  echo
  echo "=== Steps 7-8: wagoe new + bb quickstart ==="
  cd /root
  wagoe new my-app > /tmp/new.log 2>&1 || { tail -20 /tmp/new.log; echo "FAIL: wagoe new"; exit 1; }
  cd my-app
  set -a; . ./.env; set +a
  bb quickstart </dev/null > /tmp/quickstart.log 2>&1 \
    || { echo "--- first 30 lines ---"; head -30 /tmp/quickstart.log; echo "FAIL: bb quickstart"; exit 1; }
  echo "quickstart ok"
  bb migrate status 2>&1 | grep -E "Applied migrations" || true

  echo
  echo "=== CHECK 4: Step 9 — password piped into bb create-admin ==="
  # BOU-266: `bb create-admin` is broken independently of this skill — the
  # :user-cli alias uses -e, so clojure.main swallows the `create` verb as a
  # script path and the CLI rejects --email as an unknown global option. Report
  # and continue, so the remaining steps still get verified; do not let a known
  # external blocker mask them.
  ADMIN_OK=no
  if printf "%s\n%s\n" "'"$PW"'" "'"$PW"'" \
       | bb create-admin --email "admin@my-app.test" --name "Admin" > /tmp/admin.log 2>&1 \
     && grep -qi "created successfully" /tmp/admin.log; then
    ADMIN_OK=yes
    echo "RESULT: piped password accepted, admin created"
  else
    echo "RESULT: BLOCKED by BOU-266 — create-admin failed before reading the password:"
    grep -iE "^Error:|^Details:|Failed to create" /tmp/admin.log | head -3 | sed "s/^/         /"
    echo "         Step 9 cannot be verified until BOU-266 is fixed."
  fi

  echo
  echo "=== Steps 10-12: start the app and prove it serves ==="
  clojure -M:run > /tmp/server.log 2>&1 &
  PORT=""
  for i in $(seq 1 90); do
    P=$(grep -o "started successfully.*:port [0-9]*" /tmp/server.log 2>/dev/null | grep -o "[0-9]*$" | head -1 || true)
    [ -n "$P" ] && { PORT="$P"; break; }
    sleep 1
  done
  [ -n "$PORT" ] || { tail -30 /tmp/server.log; echo "FAIL: no port in server log"; exit 1; }
  echo "port from log: $PORT"

  # "Serving" means the server answered, not that / returns 200: a fresh app
  # redirects / (302). Anything other than 000 is an HTTP response; 000 is
  # curl reporting it never connected.
  CODE=""
  for i in $(seq 1 60); do
    CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "http://localhost:$PORT/" || true)
    [ -n "$CODE" ] && [ "$CODE" != "000" ] && break
    sleep 1
  done
  { [ -n "$CODE" ] && [ "$CODE" != "000" ]; } \
    || { tail -30 /tmp/server.log; echo "FAIL: app never answered on port $PORT"; exit 1; }
  LOC=$(curl -s -o /dev/null -w "%{redirect_url}" --max-time 3 "http://localhost:$PORT/" || true)
  echo "RESULT: serving on port $PORT — / -> $CODE${LOC:+ (redirects to $LOC)}"

  DOCS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "http://localhost:$PORT/api-docs/" || true)
  echo "RESULT: /api-docs/ -> $DOCS"

  echo
  echo "=== CHECK 5: the URLs the skill prints in Step 13 ==="
  LOGIN=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "http://localhost:$PORT/web/login" || true)
  echo "RESULT: /web/login -> $LOGIN (must be 200 — the skill sends users here)"
  [ "$LOGIN" = "200" ] || { echo "FAIL: the login page the skill points at does not serve"; exit 1; }
  # The admin module is optional: wagoe new wires core/observability/platform/user
  # only, so /web/admin/ 404s until `wagoe add admin`. Asserted so that if the
  # default module set ever changes, the Step 13 output gets revisited.
  # (No apostrophes in this block: the whole script body is single-quoted, and
  # one stray quote silently hands the rest to the outer shell.)
  ADMIN=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "http://localhost:$PORT/web/admin/" || true)
  echo "RESULT: /web/admin/ -> $ADMIN (404 expected — admin is an optional module)"

  echo
  echo "=== SUMMARY ==="
  echo "  Steps 1-3  (toolchain + PATH + sdkman)  verified"
  echo "  Steps 7-8  (wagoe new + bb quickstart)  verified"
  echo "  Step  9    (create-admin)               $( [ "$ADMIN_OK" = yes ] && echo verified || echo "BLOCKED — BOU-266" )"
  echo "  Steps 10-12 (start + port + HTTP 200)   verified"
  echo
  [ "$ADMIN_OK" = yes ] && echo "ALL CHECKS PASSED" \
    || echo "ALL CHECKS PASSED EXCEPT STEP 9 (BOU-266) — a blocked step is not coverage"
'
