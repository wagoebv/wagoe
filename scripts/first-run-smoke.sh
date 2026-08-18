#!/usr/bin/env bash
# First-run smoke test (BOU-231).
#
# Walks the documented path a newcomer takes — install.sh, wagoe new,
# bb quickstart, start the app — inside a *bare* container, and asserts on the
# result rather than on exit codes.
#
# Run locally exactly as CI does:
#   scripts/first-run-smoke.sh
#
# Two properties make this test worth having, and both are easy to lose:
#
#   1. It runs in a container with nothing installed. Every install.sh defect
#      found in BOU-226 needed an environment with no Java, no unzip, no sudo —
#      conditions no maintainer's laptop and no GitHub runner reproduces. Run
#      this on the runner directly and it silently proves nothing.
#
#   2. It tests THIS checkout, not the last release. install.sh installs the
#      CLI from the published tag, and generated projects pin
#      com.wagoe/wagoe-tools from Clojars — so a naive run exercises shipped
#      code and passes while the branch is broken. The :local/root rewrites
#      below are what make it a test of the working tree.
#
# Asserting on exit codes is not enough: `bb quickstart` reported 8/8 Done and
# exit 0 while the app was not running (BOU-226) and while the sample module's
# table did not exist (BOU-256). Only the HTTP and migration assertions caught
# those.
set -euo pipefail

IMAGE="${SMOKE_IMAGE:-ubuntu:24.04}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The archlinux image publishes amd64 only, so covering Arch from an Apple
# Silicon machine needs emulation. Opt-in rather than automatic: an emulated run
# is several times slower and would silently distort the timing this test
# reports.
PLATFORM_ARG=()
[ -n "${SMOKE_PLATFORM:-}" ] && PLATFORM_ARG=(--platform "$SMOKE_PLATFORM")

echo "── First-run smoke test"
echo "   image: $IMAGE${SMOKE_PLATFORM:+  (platform: $SMOKE_PLATFORM)}"
echo "   repo:  $REPO_ROOT"
echo

docker run --rm \
  ${PLATFORM_ARG[@]+"${PLATFORM_ARG[@]}"} \
  -v "$REPO_ROOT:/repo:ro" \
  -e REPO=/repo \
  "$IMAGE" bash -euo pipefail -c '
fail() { echo; echo "SMOKE FAILURE: $*"; exit 1; }
ok()   { echo "  ok — $*"; }

# ── 0. package manager ──────────────────────────────────────────────────────
# SMOKE_IMAGE has always been a parameter, but the body hardcoded apt-get, so
# pointing it at Fedora failed on packaging rather than on a defect — the matrix
# cell would look broken without telling you anything. Detect instead, and pin
# the *expected* prerequisite hint to the same detection, because that hint is
# what step 1 asserts on.
export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  PKG=apt; EXPECT_HINT="apt-get install"
  apt-get update -qq >/tmp/pkg.log 2>&1 || { tail -5 /tmp/pkg.log; fail "apt-get update failed"; }
  pkg_install() { apt-get install -y -qq "$@" >/tmp/pkg.log 2>&1 || { tail -5 /tmp/pkg.log; fail "apt-get install $* failed"; }; }
elif command -v dnf >/dev/null 2>&1; then
  PKG=dnf; EXPECT_HINT="dnf install"
  pkg_install() { dnf install -y -q "$@" >/tmp/pkg.log 2>&1 || { tail -5 /tmp/pkg.log; fail "dnf install $* failed"; }; }
elif command -v pacman >/dev/null 2>&1; then
  PKG=pacman; EXPECT_HINT="pacman -S"
  # --disable-sandbox: pacman cannot initialise its seccomp sandbox under qemu
  # emulation ("error restricting syscalls via seccomp: 22"), which is how Arch
  # is reached from an Apple Silicon host. Without it every pacman call fails.
  PAC_FLAGS="--noconfirm --disable-sandbox"
  pacman -Sy $PAC_FLAGS >/tmp/pkg.log 2>&1 \
    || { tail -5 /tmp/pkg.log; fail "pacman -Sy failed"; }
  pkg_install() { pacman -S $PAC_FLAGS --needed "$@" >/tmp/pkg.log 2>&1 || { tail -5 /tmp/pkg.log; fail "pacman -S $* failed"; }; }
else
  fail "no supported package manager (apt-get/dnf/pacman) in this image"
fi
echo "     package manager: $PKG"
pkg_install curl ca-certificates

# ── 1. bare image: prerequisites must be reported, not delegated ────────────
echo "[1/8] prerequisite detection on a bare image"
set +e
PREREQ_OUT="$(bash /repo/scripts/install.sh 2>&1)"
PREREQ_RC=$?
set -e
[ "$PREREQ_RC" -eq 0 ] && fail "installer succeeded on a bare image; it cannot have checked prerequisites"
grep -qi "missing required tool" <<<"$PREREQ_OUT" \
  || fail "no actionable prerequisite message. Got: $(tail -3 <<<"$PREREQ_OUT")"
# Distro-specific on purpose: telling a Fedora user to run apt-get is a dead
# end, and asserting on any-install-command-at-all would not catch it.
grep -qi -- "$EXPECT_HINT" <<<"$PREREQ_OUT" \
  || fail "prerequisite message does not name a $PKG install command (expected: $EXPECT_HINT). Got: $(tail -3 <<<"$PREREQ_OUT")"
ok "names the missing tools and the $PKG command to install them"

# ── 2. install ──────────────────────────────────────────────────────────────
echo "[2/8] install.sh"
pkg_install git unzip zip which
if [ "$PKG" = pacman ]; then
  # install.sh:122 installs the JVM with a plain `pacman -S`, and pacman cannot
  # sandbox inside this container: under qemu it fails with "restricting
  # syscalls via seccomp: 22", and on an ARM-native Arch image with "Landlock is
  # not supported by the kernel". Both are Docker-environment limits, not Wagoe
  # defects — a real Arch box has neither.
  #
  # Pre-satisfying java lets the REST of the Arch path be tested (OS detection,
  # prerequisite hint, Clojure CLI, bb, bbin, the CLI itself, and the whole
  # project funnel), all of which use generic installers rather than pacman.
  echo "     NOTE: pre-installing the JVM, so install.sh skips its pacman JVM"
  echo "           step. That single step is NOT under test on this image."
  pkg_install jdk-openjdk
fi
T0=$(date +%s)
bash /repo/scripts/install.sh >/tmp/install.log 2>&1 || {
  tail -20 /tmp/install.log; fail "install.sh exited non-zero"; }
for t in java clojure bb wagoe; do
  bash -ic "command -v $t" >/dev/null 2>&1 || fail "$t not on PATH after install"
done
ok "installed; java, clojure, bb, wagoe all resolve"

# ── 3. generate a project from THIS checkout ────────────────────────────────
echo "[3/8] wagoe new"
cp -r /repo /work
cd /root
bash -ic "bb --config /work/bb.edn -e \"(require (quote wagoe.cli.main)) (wagoe.cli.main/-main \\\"new\\\" \\\"demo\\\")\"" \
  >/tmp/new.log 2>&1 || { tail -20 /tmp/new.log; fail "wagoe new failed"; }
cd /root/demo
grep -vE "^\s*;;" resources/conf/dev/config.edn | grep -q ":wagoe/sqlite" \
  || fail "generated project does not default to sqlite (BOU-228)"
ok "project generated, defaults to sqlite"

# Point EVERY com.wagoe dep at this checkout. Overriding only a couple is not
# enough and fails quietly: the first version of this script rewrote platform
# and tools, and the run still exercised the *published* scaffolder, so a fixed
# migration-naming bug appeared unfixed.
#
# Artifact id maps to a directory under libs/: wagoe-core -> libs/core,
# wagoe-tools -> libs/tools, but wagoe-cli -> libs/wagoe-cli. Try the stripped
# name first, then the full one, and leave the pin alone if neither exists.
for d in /work/libs/*/; do
  name=$(basename "$d")
  case "$name" in
    wagoe-*) art="$name" ;;
    *)       art="wagoe-$name" ;;
  esac
  sed -E -i "s|com\.wagoe/${art}([[:space:]]+)\{:mvn/version \"[^\"]+\"\}|com.wagoe/${art}\1{:local/root \"${d%/}\"}|g" \
    deps.edn bb.edn
done

# Assert on what is LEFT, not on what is present. Checking merely that some
# :local/root exists would pass with one com.wagoe artifact still pinned to a
# published version — and that single pin is enough to test released code while
# the run reports success, which is the whole failure mode this guards against.
for f in deps.edn bb.edn; do
  STILL_PINNED=$(grep -oE "com\.wagoe/[a-z0-9-]+[[:space:]]*\{:mvn/version" "$f" || true)
  [ -z "$STILL_PINNED" ] \
    || fail "$f still pins published com.wagoe artifacts, so this run would test the release:
$STILL_PINNED"
done
LEFT=$(grep -c ":mvn/version" deps.edn || true)
ok "no com.wagoe dep left on a published version ($LEFT third-party pins untouched)"

# ── 4. quickstart ───────────────────────────────────────────────────────────
echo "[4/8] bb quickstart"
set -a; . ./.env 2>/dev/null || true; set +a
# The scaffolder is injected via -Sdeps at a hardcoded version rather than read
# from deps.edn, so the :local/root rewrite above cannot reach it. Without this
# the scaffolding step silently runs the released scaffolder.
export WAGOE_SCAFFOLDER_ROOT=/work/libs/scaffolder
bash -ic "bb quickstart" </dev/null >/tmp/quickstart.log 2>&1 \
  || { tail -25 /tmp/quickstart.log; fail "bb quickstart exited non-zero"; }
grep -vE "^\s*;;" resources/conf/dev/config.edn | grep -q ":wagoe/sqlite" \
  || fail "quickstart overwrote the working config (BOU-228)"
ok "completed without clobbering the config"

# ── 5. the scaffolded migration must actually apply ─────────────────────────
# `bb quickstart` reports 8/8 Done even when zero migrations run, which is how
# BOU-256 stayed hidden: the sample module had no table.
echo "[5/8] scaffolded migration applied"
if ls migrations/*.sql >/dev/null 2>&1; then
  STATUS="$(bash -ic "bb migrate status" 2>&1 || true)"
  APPLIED="$(grep -oE "Applied migrations: [0-9]+" <<<"$STATUS" | grep -oE "[0-9]+" | tail -1)"
  [ "${APPLIED:-0}" -ge 1 ] \
    || fail "migrations/ has files but migratus applied ${APPLIED:-0} (BOU-256: filename must be <id>-<name>.up.sql)"
  ok "migratus applied ${APPLIED} migration(s)"
else
  fail "quickstart scaffolded no migration at all"
fi

# ── 6. do the gates pass on what the scaffolder just produced? ──────────────
# `bb quickstart` above scaffolds a sample module, and AGENTS.md tells the user
# to run `bb check`. Nobody had ever run both: this script stopped before
# `bb check`, and BOU-264 verified `bb check` on a bare project with no module
# in it. The combination was the gap, and it hid untagged deftests, an
# `(is true)`, 36 lint warnings, a protocol method declared twice, and a service
# calling a repository method that did not exist (BOU-267).
echo "[6/8] bb check on the scaffolded module"
# --ci is load-bearing. Without it `bb check` prints its ✗ lines and still exits
# 0 — it only calls System/exit on failure when :ci is set (check.clj). The
# first version of this step omitted it and was therefore a gate that could
# never fail; measured on a project with two real violations:
#   bb check      -> exit 0, "7 passed, 2 failed"
#   bb check --ci -> exit 1, "7 passed, 2 failed"
set +e
bash -ic "cd /root/demo && bb check --ci" >/tmp/check.log 2>&1
CHECK_RC=$?
set -e
[ "$CHECK_RC" -eq 0 ] \
  || { grep -E "✗|Summary" /tmp/check.log | head -12
       fail "bb check failed on a freshly scaffolded module (BOU-267)"; }
# Assert the summary too, not only the exit code. One exit code is exactly what
# hid this, and a future change to when --ci exits would silently disarm the
# step again.
grep -qE "Summary: .*0 failed" /tmp/check.log \
  || { grep -E "Summary" /tmp/check.log | head -3
       fail "bb check exited 0 but its summary does not say 0 failed"; }
grep -q "Skipped .* framework-only" /tmp/check.log \
  || fail "bb check did not report the framework-only checks it skipped (BOU-264)"
ok "gates pass, and the skipped framework-only checks are named"

# ── 7. can you create the admin user you are told to create? ────────────────
# Added after BOU-266: `bb create-admin` could not create a user at all — the
# :user-cli alias ran the CLI through -e, so clojure.main swallowed the `create`
# verb and the CLI rejected --email as an unknown global option. Nothing caught
# it, because this script stopped at "app serves HTTP" and the tests for the
# user CLI call run-cli! directly with a well-formed vector, which is precisely
# the step the alias got wrong. Assert on the outcome, not the exit code.
# (No apostrophes below: this whole body is a single-quoted docker argument.)
echo "[7/8] bb create-admin"
ADMIN_PW="Str0ng-Dev-Pass-x9"
set +e
printf "%s\n%s\n" "$ADMIN_PW" "$ADMIN_PW" \
  | bash -ic "cd /root/demo && set -a && . ./.env && set +a && bb create-admin --email admin@demo.test --name Admin" \
    >/tmp/admin.log 2>&1
ADMIN_RC=$?
set -e
# Exit code AND the exact success line. Neither alone is enough: the first
# version of this check grepped case-insensitively for "created successfully",
# which matches a DDL log line the user CLI emits while initialising the schema
# — so it passed with the BOU-266 bug still present, printing "Failed to create
# admin user." two lines above its own ok. Assert on what the command reports
# about itself, not on words that appear somewhere in its output.
[ "$ADMIN_RC" -eq 0 ] \
  || { grep -iE "^Error:|^Details:|Failed to create" /tmp/admin.log | head -3
       fail "bb create-admin exited $ADMIN_RC (BOU-266: the :user-cli alias must pass the verb through)"; }
grep -q "Admin user created successfully" /tmp/admin.log \
  || { tail -5 /tmp/admin.log
       fail "bb create-admin exited 0 without reporting that it created the user"; }
ok "admin user created"

# ── 7. does it actually serve? ──────────────────────────────────────────────
echo "[8/8] app serves HTTP"
bash -ic "cd /root/demo && set -a && . ./.env && set +a && clojure -M:repl" >/tmp/repl.log 2>&1 &
for _ in $(seq 1 90); do (echo > /dev/tcp/127.0.0.1/7888) 2>/dev/null && break; sleep 2; done
(echo > /dev/tcp/127.0.0.1/7888) 2>/dev/null || { tail -25 /tmp/repl.log; fail "nREPL never came up"; }
bash -ic "clj-nrepl-eval -p 7888 \"(go)\"" >/tmp/go.log 2>&1 || { tail -15 /tmp/go.log; fail "(go) failed"; }
CODE=000
for _ in $(seq 1 45); do
  # `|| true`, not `|| echo 000`: on connection refused curl already prints 000
  # via -w AND exits non-zero, so `|| echo 000` yields "000000" — which is not
  # equal to "000", breaking the retry loop on the first attempt and failing
  # with a nonsense status.
  CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://localhost:3000/api-docs/ || true)
  [ "$CODE" != "000" ] && break
  sleep 2
done
[ "$CODE" = "200" ] || { tail -25 /tmp/repl.log; fail "/api-docs/ returned $CODE, expected 200"; }
T1=$(date +%s)
ok "/api-docs/ returned 200"

# ── does the module quickstart scaffolded serve anything? ───────────────────
# The check above only proves that *some* server is up. /api-docs/ is served by
# the framework and answers 200 in a project with no module in it at all, which
# is how scaffold -> integrate -> dead code survived for months: quickstart
# reported 8/8 Done and this script reported success while /api/v1/tasks was a
# 404 (BOU-309, BOU-310, BOU-311).
MODULE_CODE=$(curl -s -o /tmp/tasks.json -w "%{http_code}" --max-time 5 http://localhost:3000/api/v1/tasks || true)
case "$MODULE_CODE" in
  2*) ;;
  404) tail -25 /tmp/repl.log
       fail "/api/v1/tasks returned 404 — quickstart scaffolded and integrated the module, but nothing mounted its routes" ;;
  *)   head -c 400 /tmp/tasks.json; echo; tail -25 /tmp/repl.log
       fail "/api/v1/tasks returned $MODULE_CODE, expected 2xx" ;;
esac
# The status alone is not the assertion. Assert on the body too: a handler that
# is mounted but returns nothing usable is not a module that serves.
head -c 1 /tmp/tasks.json | grep -qE "[[{]" \
  || { head -c 400 /tmp/tasks.json; echo
       fail "/api/v1/tasks returned $MODULE_CODE but the body is not JSON"; }
# And the control. A router that answers every path under the prefix passes the
# two asserts above while proving nothing, so require an unknown sibling to 404.
NOPE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:3000/api/v1/not-a-module || true)
[ "$NOPE" = "404" ] \
  || fail "/api/v1/not-a-module returned $NOPE instead of 404, so the 2xx above proves nothing"
ok "/api/v1/tasks returned $MODULE_CODE with a JSON body, and unknown paths still 404"

echo
echo "First-run smoke passed in $((T1-T0))s (install to serving app)."
'
