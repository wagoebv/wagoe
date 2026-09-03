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
#   2. It knows WHICH Wagoe it is testing, and says so. install.sh installs the
#      CLI from the published tag, and generated projects pin
#      com.wagoe/wagoe-tools from Clojars — so a naive run exercises shipped
#      code and passes while the branch is broken. SMOKE_TARGET picks the one
#      under test, and each mode asserts that it got what it asked for:
#
#        worktree (default) — rewrite every com.wagoe pin to :local/root, then
#          fail if any pin survived. Gates every push, in ci.yml.
#        released           — leave the pins alone, then fail if any :local/root
#          crept in. Runs the published tag exactly as a visitor gets it.
#
# Why `released` exists (BOU-402). The worktree mode is the configuration in
# which a stale release passes: 1.0.0-beta-5 shipped the pre-BOU-319 dev/user.clj
# — thirteen lines of go/reset/halt — so `bb quickstart` closed by telling users
# to run (status), and (status) did not resolve. main had the fix; the tag
# predated it, and nothing exercised the tag. The same run catches BOU-401.
#
# Both modes run the installer and the assertions from THIS checkout. Only the
# artifacts differ. Curling get.wagoe.org instead would make steps 1-2 test a CDN
# rather than scripts/install.sh, and stop reporting installer defects.
#
# Asserting on exit codes is not enough: `bb quickstart` reported 8/8 Done and
# exit 0 while the app was not running (BOU-226) and while the sample module's
# table did not exist (BOU-256). Only the HTTP and migration assertions caught
# those.
set -euo pipefail

IMAGE="${SMOKE_IMAGE:-ubuntu:24.04}"
TARGET="${SMOKE_TARGET:-worktree}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "$TARGET" in
  worktree|released) ;;
  *) echo "SMOKE_TARGET must be worktree or released, got: $TARGET" >&2; exit 2 ;;
esac

# The archlinux image publishes amd64 only, so covering Arch from an Apple
# Silicon machine needs emulation. Opt-in rather than automatic: an emulated run
# is several times slower and would silently distort the timing this test
# reports.
PLATFORM_ARG=()
[ -n "${SMOKE_PLATFORM:-}" ] && PLATFORM_ARG=(--platform "$SMOKE_PLATFORM")

echo "── First-run smoke test"
echo "   image:  $IMAGE${SMOKE_PLATFORM:+  (platform: $SMOKE_PLATFORM)}"
echo "   repo:   $REPO_ROOT"
echo "   target: $TARGET"
echo

docker run --rm \
  ${PLATFORM_ARG[@]+"${PLATFORM_ARG[@]}"} \
  -v "$REPO_ROOT:/repo:ro" \
  -e REPO=/repo \
  -e "TARGET=$TARGET" \
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
# Separate assert, separate message. install.sh installs this one with `|| true`
# and reports "AI agent tooling installed" either way, so a failed bbin clone is
# silent here and resurfaces at step 8 as "(go) failed" — which sends you to
# debug the application. Say it where it happened.
bash -ic "command -v clj-nrepl-eval" >/dev/null 2>&1 \
  || fail "clj-nrepl-eval not on PATH after install (install.sh installs it with || true, so this is a failed bbin install, not a Wagoe defect — usually GitHub throttling)"
# Name the tag. In released mode it IS the thing under test, and a red cell that
# does not say which release it tested sends the reader to the Actions log to
# find out. install.sh resolves it from the GitHub releases API, so it is not
# derivable from this checkout.
# install.sh colours that line and ends it with an ellipsis, so the raw match is
# "1.0.0-beta-5...<ESC>[0m". Strip both, or the tag reported is not a tag.
# `|| true` is load-bearing under `set -euo pipefail`: a grep that matches
# nothing exits 1, and the exit status of a command substitution is the
# assignment/s, so a changed install.sh message would kill the run here with no
# output at all. Reporting an unknown tag is the correct failure for a cosmetic
# line.
WAGOE_TAG=$(grep -oE "Installing wagoe CLI @ .*" /tmp/install.log \
              | head -1 \
              | sed -E "s/.*@ //; s/\x1B\[[0-9;]*[a-zA-Z]//g; s/[.[:space:]]+$//" || true)
ok "installed; java, clojure, bb, wagoe all resolve (published tag: ${WAGOE_TAG:-unknown})"

# ── 3. generate a project ───────────────────────────────────────────────────
# worktree: drive the CLI out of the copied checkout, so the generator under
#           test is this branch.
# released: use the `wagoe` on PATH, which install.sh built from the latest
#           published tag. That wrapper is the whole point of this mode — it
#           runs the templates in that tag, which is where BOU-402 lived.
echo "[3/8] wagoe new  (target: $TARGET)"
cd /root
if [ "$TARGET" = worktree ]; then
  cp -r /repo /work
  bash -ic "bb --config /work/bb.edn -e \"(require (quote wagoe.cli.main)) (wagoe.cli.main/-main \\\"new\\\" \\\"demo\\\")\"" \
    >/tmp/new.log 2>&1 || { tail -20 /tmp/new.log; fail "wagoe new failed"; }
else
  bash -ic "wagoe new demo" </dev/null >/tmp/new.log 2>&1 \
    || { tail -20 /tmp/new.log; fail "wagoe new failed"; }
fi
cd /root/demo
grep -vE "^\s*;;" resources/conf/dev/config.edn | grep -q ":wagoe/sqlite" \
  || fail "generated project does not default to sqlite (BOU-228)"
ok "project generated, defaults to sqlite"

if [ "$TARGET" = worktree ]; then
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
else
  # The mirror image, and it has to be asserted rather than assumed. A run that
  # silently picked up a :local/root would report a green release while proving
  # only that the working tree is fine — the exact blindness this mode exists to
  # remove. Assert a published pin is actually present too: a template that
  # stopped emitting com.wagoe deps would satisfy a no-:local/root check alone.
  for f in deps.edn bb.edn; do
    LOCAL=$(grep -oE "com\.wagoe/[a-z0-9-]+[[:space:]]*\{:local/root" "$f" || true)
    [ -z "$LOCAL" ] \
      || fail "$f points com.wagoe deps at a local checkout, so this run would NOT test the release:
$LOCAL"
  done
  PINNED=$(grep -cE "com\.wagoe/[a-z0-9-]+[[:space:]]*\{:mvn/version" deps.edn || true)
  [ "${PINNED:-0}" -ge 1 ] \
    || fail "deps.edn pins no published com.wagoe artifact at all — nothing released is under test"
  ok "every com.wagoe dep is a published version ($PINNED in deps.edn)"
fi

# ── 4. quickstart ───────────────────────────────────────────────────────────
echo "[4/8] bb quickstart"
set -a; . ./.env 2>/dev/null || true; set +a
# The scaffolder is injected via -Sdeps at a hardcoded version rather than read
# from deps.edn, so the :local/root rewrite above cannot reach it. Without this
# the scaffolding step silently runs the released scaffolder — which is exactly
# what the released target wants, so only worktree sets it.
if [ "$TARGET" = worktree ]; then
  export WAGOE_SCAFFOLDER_ROOT=/work/libs/scaffolder
fi
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
ok "/api-docs/ returned 200"

# ── does the module quickstart scaffolded serve anything? ───────────────────
# The check above only proves that *some* server is up. /api-docs/ is served by
# the framework and answers 200 in a project with no module in it at all, which
# is how scaffold -> integrate -> dead code survived for months: quickstart
# reported 8/8 Done and this script reported success while /api/v1/tasks was a
# 404 (BOU-309, BOU-310, BOU-311).
#
# The path comes from the ENTITY, not the module: quickstart passes
# --module-name tasks --entity Task, and the scaffolder pluralises the entity.
# Change either in quickstart.clj and this URL has to change with it.
#
# Retried, and only while the status is 000. Every route is compiled in one pass
# inside init-key :wagoe/http-handler, so once /api-docs/ answers, this route
# exists or never will — a 404 is an answer and must not be retried away. What
# is not inherited from the loop above is latency: this is the first request
# through the /api/v1 pipeline, all of it cold, on an emulated matrix cell.
MODULE_CODE=000
for _ in $(seq 1 10); do
  MODULE_CODE=$(curl -s -o /tmp/tasks.json -w "%{http_code}" --max-time 10 http://localhost:3000/api/v1/tasks || true)
  [ "$MODULE_CODE" != "000" ] && break
  sleep 2
done
# `|| true` on every read of the body file. curl writes no file at all when it
# never gets a response, and a bare `head` on a missing file is a non-zero exit
# under `set -e` — which killed the script inside its own failure branch,
# printing a head: error and no SMOKE FAILURE line.
body_() { head -c 400 /tmp/tasks.json 2>/dev/null || true; echo; }
case "$MODULE_CODE" in
  2*) ;;
  404) tail -25 /tmp/repl.log
       fail "/api/v1/tasks returned 404 — quickstart scaffolded and integrated the module, but nothing mounted its routes" ;;
  000) tail -25 /tmp/repl.log
       fail "/api/v1/tasks never answered within 10 attempts" ;;
  *)   body_; tail -25 /tmp/repl.log
       fail "/api/v1/tasks returned $MODULE_CODE, expected 2xx" ;;
esac
# The status alone is not the assertion. Assert on the body too: a handler that
# is mounted but returns nothing usable is not a module that serves.
head -c 1 /tmp/tasks.json 2>/dev/null | grep -qE "[[{]" \
  || { body_; fail "/api/v1/tasks returned $MODULE_CODE but the body is not JSON"; }
# The control. Today the router installs a default 404 handler, so this holds in
# the broken state too — it does not discriminate. It guards the future: a
# catch-all, or a prefix mounted too greedily, would satisfy both asserts above.
NOPE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 http://localhost:3000/api/v1/not-a-module || true)
[ "$NOPE" = "404" ] \
  || fail "/api/v1/not-a-module returned $NOPE instead of 404 — an unknown path must not be served"
ok "/api/v1/tasks returned $MODULE_CODE with a JSON body, and unknown paths still 404"

# ── are the devtools the docs point at on the classpath? ────────────────────
# devtools ships the BND error pipeline, (fix!) and the dashboard, and until
# BOU-318 it reached generated projects nowhere: not in :deps, not in the :repl
# alias, not in the module catalogue. Loading it through the REPL that is
# already running is the assert — a classpath grep would pass on a jar that
# cannot load.
#
# The value asserted on is COMPUTED, not a literal in the expression. The first
# version evaluated `(require ...) :loaded` and grepped for :loaded, which
# clj-nrepl-eval echoes back as part of the code it was given: the step passed
# with devtools removed from the template entirely. A count cannot be echoed.
bash -ic "clj-nrepl-eval -p 7888 \"(do (require (quote wagoe.devtools.core.error-classifier)) (str (quote devtools-classify=) (some? (resolve (quote wagoe.devtools.core.error-classifier/classify)))))\"" \
  >/tmp/devtools.log 2>&1 || { tail -15 /tmp/devtools.log
                               fail "wagoe.devtools is not loadable in the generated project (deps.edn :repl alias)"; }
grep -qE "devtools-classify=true" /tmp/devtools.log \
  || { tail -15 /tmp/devtools.log
       fail "wagoe.devtools did not load — classify does not resolve"; }
T1=$(date +%s)
ok "wagoe-devtools loads from the :repl alias"

# ── do the REPL helpers quickstart names exist? ─────────────────────────────
# `bb quickstart` closes with "run (status), run (commands)". Both lived only
# in the dev/repl/user.clj of this monorepo: the generated one was thirteen
# lines of go/reset/halt, so the first instruction a new user follows answered
# "Unable to resolve symbol: status" (BOU-319).
#
# This is the step the released target exists for. BOU-319 fixed the template on
# main and this assertion has passed ever since — on the working tree. The tag
# users actually install kept generating the thirteen-line version for another
# three weeks, and no run was configured to notice (BOU-402).
#
# Asserted on a computed value again — clj-nrepl-eval echoes the code it is
# given, so any literal in the expression is already in the output. The
# dashboard box is drawn by devtools from the running system.
#
# The exit code is not the assert. clj-nrepl-eval returns 0 for an eval that
# errored — against 1.0.0-beta-5 it exits 0 and prints "No such var:
# user/status" — so the `|| fail` below catches a broken connection and nothing
# else. What discriminates is whether the dashboard is in the output.
bash -ic "clj-nrepl-eval -p 7888 \"(with-out-str (user/status))\"" >/tmp/status.log 2>&1 \
  || { tail -15 /tmp/status.log; fail "could not reach the nREPL to evaluate (status)"; }
grep -q "Wagoe Dev" /tmp/status.log \
  || { tail -15 /tmp/status.log
       fail "(status) printed no dashboard — it does not exist, or resolved to something that is not the devtools helper"; }
grep -q "tasks" /tmp/status.log \
  || { tail -15 /tmp/status.log
       fail "(status) does not list the scaffolded module among the running ones"; }
bash -ic "clj-nrepl-eval -p 7888 \"(with-out-str (user/commands))\"" >/tmp/commands.log 2>&1 \
  || { tail -15 /tmp/commands.log; fail "(commands) threw"; }
grep -q "SYSTEM:" /tmp/commands.log \
  || { tail -15 /tmp/commands.log; fail "(commands) printed no palette"; }
ok "(status) and (commands) work in the generated project"

# ── does a bad request explain itself? ──────────────────────────────────────
# A validation failure used to answer "Validation failed" and nothing else. The
# pipeline that names the BND code and the fix lived in devtools, which reached
# no downstream classpath, and its classifier did not recognise
# :validation-error — the type every Wagoe handler raises (BOU-321).
#
# POST /auth/login with an empty body: coercion rejects it before any handler
# runs, which is the error path a beginner meets first. Not the scaffolded
# module: its generated handlers are stubs that validate nothing and answer 201.
LOGIN_CODE=$(curl -s -o /tmp/badreq.json -w "%{http_code}" --max-time 10 \
                  -X POST -H "Content-Type: application/json" -d "{}" \
                  http://localhost:3000/api/v1/auth/login || true)
case "$LOGIN_CODE" in
  400) ;;
  *)   head -c 400 /tmp/badreq.json 2>/dev/null; echo
       fail "POST /api/v1/auth/login with an empty body returned $LOGIN_CODE, expected 400" ;;
esac
grep -q "BND-" /tmp/badreq.json \
  || { head -c 600 /tmp/badreq.json 2>/dev/null; echo
       fail "the 400 carries no BND code — dev error enrichment is not wired in a generated project"; }
# The enrichment is dev-only. Nothing here can run the app as production — the
# generated project has no prod config — so the negative is asserted in
# wagoe.platform.shell.http.reitit-router-test and interceptors-test instead,
# with the profile the wiring passes through.
ok "a malformed request answers 400 with a BND code"

echo
if [ "$TARGET" = released ]; then
  echo "First-run smoke passed in $((T1-T0))s (install to serving app), target: released ${WAGOE_TAG:-unknown}."
else
  echo "First-run smoke passed in $((T1-T0))s (install to serving app), target: worktree."
fi
'
