#!/usr/bin/env bash
# First-run preconditions (BOU-232).
#
# The environment cells the matrix promised and never had: a machine with a JDK
# too old to use, one that cannot reach the network, and one whose IP has spent
# GitHub's unauthenticated API budget (BOU-410). Each needs a container of its
# own — the smoke and adversarial suites each install once into a container they
# then reuse, and neither shape can host these.
#
# All three assert on the *message*. A first-run tool that fails is survivable;
# one that fails without saying what to do next is what makes someone close the
# tab — and one that names the wrong cause sends them to fix their router.
#
# Run:
#   scripts/first-run-preconditions.sh
#   PRE_IMAGE=fedora:41 scripts/first-run-preconditions.sh
set -euo pipefail

CASES=3

IMAGE="${PRE_IMAGE:-ubuntu:24.04}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PLATFORM_ARG=()
[ -n "${PRE_PLATFORM:-}" ] && PLATFORM_ARG=(--platform "$PRE_PLATFORM")

echo "── First-run preconditions"
echo "   image: $IMAGE${PRE_PLATFORM:+  (platform: $PRE_PLATFORM)}"
echo "   repo:  $REPO_ROOT"
echo

FAILED=0

# =============================================================================
# 1. A JDK older than the one Wagoe needs
# =============================================================================
#
# install.sh tested `java -version 2>&1 | grep -q "version"`, which every JDK
# back to 8 passes. On a machine with an old JDK the installer reported "JVM
# already installed" and carried on, and the failure surfaced much later as a
# class-file-version error out of the Clojure compiler — which tells a newcomer
# nothing about what to do. The installer's own text says "JDK 21+".
echo "[1/$CASES] a JDK older than 21 is not silently accepted"
if docker run --rm \
  ${PLATFORM_ARG[@]+"${PLATFORM_ARG[@]}"} \
  -v "$REPO_ROOT:/repo:ro" \
  "$IMAGE" bash -euo pipefail -c '
fail() { echo; echo "  PRECONDITION FAILURE: $*"; exit 1; }
ok()   { echo "  ok — $*"; }

export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq >/tmp/pkg.log 2>&1
  apt-get install -y -qq curl ca-certificates git unzip zip which openjdk-17-jdk-headless >/tmp/pkg.log 2>&1 \
    || { tail -5 /tmp/pkg.log; fail "could not install the old JDK the case needs"; }
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y -q curl ca-certificates git unzip zip which java-17-openjdk-headless >/tmp/pkg.log 2>&1 \
    || { tail -5 /tmp/pkg.log; fail "could not install the old JDK the case needs"; }
else
  fail "no supported package manager for this case"
fi

FOUND=$(java -version 2>&1 | head -1)
grep -q "17\." <<<"$FOUND" || fail "expected a JDK 17 for this case, got: $FOUND"
ok "container has $FOUND"

set +e
OUT="$(bash /repo/scripts/install.sh 2>&1)"
set -e

# The installer must not claim the JVM requirement is already met.
if grep -qi "JVM already installed (Java 17)" <<<"$OUT"; then
  fail "accepted Java 17 as sufficient"
fi
if grep -qi "JVM already installed" <<<"$OUT" && ! grep -qi "Java 2[1-9]\|Java [3-9][0-9]" <<<"$OUT"; then
  fail "reported the JVM as already installed without naming a version it checked"
fi

# It must say what it found and that it is too old, in words, before doing
# anything about it.
grep -qi "Java 17 is on PATH" <<<"$OUT" \
  || fail "never said which Java it found. Got: $(tail -5 <<<"$OUT")"
grep -qi "21 or newer" <<<"$OUT" \
  || fail "never said which version is required. Got: $(tail -5 <<<"$OUT")"
ok "named the version it found and the one it needs"

# Whatever it then does — install one, or refuse — the shell the user opens
# next must not still be on 17.
#
# It has to be a fresh interactive shell, not this one. sdkman puts its JDK on
# PATH by way of an init script that only shells reading their rc will source,
# so *this* process still sees the old java however well the install went. The
# smoke test reaches the new toolchain the same way.
NEXT_MAJOR=$(bash -ic "java -version" 2>&1 | sed -nE "s/.*version .([0-9]+).*/\1/p" | head -1)
if [ -n "$NEXT_MAJOR" ] && [ "$NEXT_MAJOR" -ge 21 ] 2>/dev/null; then
  ok "the next shell the user opens gets Java $NEXT_MAJOR"
else
  # Refusing is a fine outcome — but only out loud, naming which java wins.
  grep -qiE "still first on PATH|✗" <<<"$OUT" \
    || fail "next shell is still on Java ${NEXT_MAJOR:-unknown} and nothing said so. Got: $(tail -5 <<<"$OUT")"
  ok "refused, and said which java is winning"
fi
'; then
  echo "  → passed"
else
  echo "  → FAILED"
  FAILED=$((FAILED + 1))
fi
echo

# =============================================================================
# 2. No network
# =============================================================================
#
# The prerequisite check passes — curl and friends are present — and the first
# download then fails. BOU-262 fixed one instance of this (a transient sdkman
# 503 aborting with raw curl output and nothing from Wagoe); nothing asserts the
# general case, and offline is a cell BOU-232 lists and the matrix never had.
echo "[2/$CASES] no network fails with a message, not a stack trace"

# Prerequisites cannot be installed with the network off, so bake them in with
# it on and then take it away. Running the bare image offline would fail at the
# prerequisite check and prove only that apt needs a network.
PREPPED="wagoe-preconditions-offline:$$"
CONTAINER="wagoe-preconditions-prep-$$"
cleanup_offline() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker rmi -f "$PREPPED" >/dev/null 2>&1 || true
}
trap cleanup_offline EXIT

docker run --name "$CONTAINER" \
  ${PLATFORM_ARG[@]+"${PLATFORM_ARG[@]}"} \
  "$IMAGE" bash -euo pipefail -c '
export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq >/dev/null 2>&1
  apt-get install -y -qq curl ca-certificates git unzip zip which >/dev/null 2>&1
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y -q curl ca-certificates git unzip zip which >/dev/null 2>&1
fi
' >/dev/null 2>&1 || { echo "  → FAILED (could not prepare the offline image)"; exit 1; }
docker commit "$CONTAINER" "$PREPPED" >/dev/null

if docker run --rm --network none \
  ${PLATFORM_ARG[@]+"${PLATFORM_ARG[@]}"} \
  -v "$REPO_ROOT:/repo:ro" \
  "$PREPPED" bash -euo pipefail -c '
fail() { echo; echo "  PRECONDITION FAILURE: $*"; exit 1; }
ok()   { echo "  ok — $*"; }

command -v curl >/dev/null 2>&1 || fail "the prepared image has no curl"

set +e
OUT="$(bash /repo/scripts/install.sh 2>&1)"
RC=$?
set -e

[ "$RC" -eq 0 ] && fail "installer reported success with no network"
ok "exited non-zero"

# The distinguishing property: Wagoe said something. A run that ends in raw
# curl output has told the user that curl failed, not what to do.
grep -qE "✗|Wagoe|re-run|install" <<<"$OUT" \
  || fail "no Wagoe-authored message; the user sees only tool output. Got: $(tail -5 <<<"$OUT")"
ok "failed with an actionable message"

grep -qiE "Traceback|Exception in thread|at [a-z]+\.[a-z]+\.[A-Z]" <<<"$OUT" \
  && fail "surfaced a stack trace: $(grep -m1 -iE "Traceback|Exception in thread" <<<"$OUT")"
ok "no stack trace"
'; then
  echo "  → passed"
else
  echo "  → FAILED"
  FAILED=$((FAILED + 1))
fi

echo

# =============================================================================
# 3. GitHub's API rate limit is not a broken connection
# =============================================================================
#
# The release lookup is unauthenticated, so it draws on GitHub's 60-per-hour
# per-IP budget. `curl -f` made every outcome exit 22, and the installer then
# said "Check your internet connection" on a working one — unactionable, and
# re-running fails identically for the rest of the hour. This is not
# hypothetical: it reddened Smoke — fedora:41 in matrix run 33758142274 while
# the ubuntu and arch jobs passed the same minute (BOU-410).
#
# A curl shim answers that one URL the way a throttled GitHub does — 403 with
# x-ratelimit-remaining: 0 — and delegates every other call to the real curl,
# so the installer still reaches the lookup normally.
echo "[3/$CASES] a rate-limited release lookup is not blamed on the network"

if docker run --rm \
  ${PLATFORM_ARG[@]+"${PLATFORM_ARG[@]}"} \
  -v "$REPO_ROOT:/repo:ro" \
  "$IMAGE" bash -euo pipefail -c '
fail() { echo; echo "  PRECONDITION FAILURE: $*"; exit 1; }
ok()   { echo "  ok — $*"; }

# The same prerequisite set the other two cases bake in. With only curl the
# installer stops at its own prerequisite check and never reaches the lookup
# this case is about.
export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq >/dev/null 2>&1
  apt-get install -y -qq curl ca-certificates git unzip zip which >/dev/null 2>&1
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y -q curl ca-certificates git unzip zip which >/dev/null 2>&1
fi
REAL_CURL="$(command -v curl)"

mkdir -p /shim
cat > /shim/curl <<SHIM
#!/usr/bin/env bash
# Emulate the throttled answer for the release lookup only. No -f, so curl
# succeeds and reports 403 as the status — exactly what the real one does.
for arg in "\$@"; do
  case "\$arg" in
    *api.github.com/repos/wagoebv/wagoe/releases/latest*)
      HDR=""; BODY=""
      while [ \$# -gt 0 ]; do
        case "\$1" in
          -D) HDR="\$2"; shift 2 ;;
          -o) BODY="\$2"; shift 2 ;;
          *)  shift ;;
        esac
      done
      RESET=\$(( \$(date +%s) + 1800 ))
      [ -n "\$HDR" ] && printf "HTTP/2 403\r\nx-ratelimit-limit: 60\r\nx-ratelimit-remaining: 0\r\nx-ratelimit-reset: \$RESET\r\n\r\n" > "\$HDR"
      [ -n "\$BODY" ] && printf "%s" "{\"message\":\"API rate limit exceeded\"}" > "\$BODY"
      printf "403"
      exit 0
      ;;
  esac
done
exec "$REAL_CURL" "\$@"
SHIM
chmod +x /shim/curl
export PATH="/shim:$PATH"

set +e
OUT="$(bash /repo/scripts/install.sh 2>&1)"
RC=$?
set -e

[ "$RC" -eq 0 ] && fail "installer reported success while the lookup was refused"
ok "exited non-zero"

grep -qi "rate limit" <<<"$OUT" \
  || fail "never named the rate limit. Got: $(tail -5 <<<"$OUT")"
ok "named the rate limit"

grep -qi "GITHUB_TOKEN" <<<"$OUT" \
  || fail "did not offer the token that raises the limit"
ok "offered GITHUB_TOKEN as the way out"

grep -qE "Retry in [0-9]+ min" <<<"$OUT" \
  || fail "did not say when to retry. Got: $(grep -i -m2 "rate limit" <<<"$OUT")"
ok "said when to retry"

# The regression itself: a working connection must not be blamed.
grep -qi "Check your internet connection" <<<"$OUT" \
  && fail "still blames the connection: $(grep -m1 -i "internet connection" <<<"$OUT")"
ok "did not blame the connection"
'; then
  echo "  → passed"
else
  echo "  → FAILED"
  FAILED=$((FAILED + 1))
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "Preconditions: all $CASES cases passed."
else
  echo "Preconditions: $FAILED case(s) failed — each is first-run friction."
  exit 1
fi
