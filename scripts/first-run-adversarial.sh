#!/usr/bin/env bash
# First-run adversarial edge cases (BOU-232).
#
# `first-run-smoke.sh` (BOU-231) walks the happy path and asserts it ends in a
# serving app. This walks the paths a newcomer takes by accident: a directory
# that already exists, a port already bound, a half-finished install re-run, a
# project name the scaffolder cannot use, a second project on the same machine.
#
# Every case here asserts on the *message*, not the exit code. A first-run tool
# that fails is survivable; one that fails without saying what to do next is
# what makes someone close the tab. Several of the BOU-226/231 defects exited
# non-zero with nothing actionable, and an exit-code assertion would have called
# that a pass.
#
# Run:
#   scripts/first-run-adversarial.sh
#   ADV_IMAGE=fedora:41 scripts/first-run-adversarial.sh
#
# Installs once, then runs every case in the same container — the install is the
# slow part, and these cases are about what happens *after* it.
set -euo pipefail

IMAGE="${ADV_IMAGE:-ubuntu:24.04}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# See first-run-smoke.sh: the archlinux image is amd64-only, so Arch coverage
# from Apple Silicon is opt-in emulation.
PLATFORM_ARG=()
[ -n "${ADV_PLATFORM:-}" ] && PLATFORM_ARG=(--platform "$ADV_PLATFORM")

# An empty directory bind-mounted read-only, for case 6. A read-only *mount* is
# enforced by the kernel for every uid, unlike permission bits, which root
# ignores — so this is what lets that case actually run in a container whose
# processes are root. Before this it always SKIPped, which meant the case had
# never run anywhere.
RO_DIR="$(mktemp -d)"
trap 'rmdir "$RO_DIR" 2>/dev/null || true' EXIT

echo "── First-run adversarial cases"
echo "   image: $IMAGE${ADV_PLATFORM:+  (platform: $ADV_PLATFORM)}"
echo "   repo:  $REPO_ROOT"
echo

docker run --rm \
  ${PLATFORM_ARG[@]+"${PLATFORM_ARG[@]}"} \
  -v "$REPO_ROOT:/repo:ro" \
  -v "$RO_DIR:/ro:ro" \
  -e REPO=/repo \
  "$IMAGE" bash -euo pipefail -c '
FAILED=0
SKIPPED=0
fail()  { echo "  FAIL — $*"; FAILED=$((FAILED+1)); }
ok()    { echo "  ok   — $*"; }
head_() { echo; echo "$*"; }

export DEBIAN_FRONTEND=noninteractive
# The package LIST is per manager, not shared. Two of these names differ on
# Arch — procps is procps-ng there, and python3 is python — so a shared list
# aborts setup before a single adversarial case runs, and the Arch knob silently
# tests nothing. (The python3 *binary* does exist on Arch once `python` is
# installed; it is a symlink. Only the package name differs.)
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq >/tmp/pkg.log 2>&1 || { tail -5 /tmp/pkg.log; echo "apt-get update failed"; exit 1; }
  pkg_install() { apt-get install -y -qq "$@" >/tmp/pkg.log 2>&1 || { tail -5 /tmp/pkg.log; echo "apt-get install $* failed"; exit 1; }; }
  PKGS="curl ca-certificates git unzip zip which procps python3 fish zsh"
elif command -v dnf >/dev/null 2>&1; then
  pkg_install() { dnf install -y -q "$@" >/tmp/pkg.log 2>&1 || { tail -5 /tmp/pkg.log; echo "dnf install $* failed"; exit 1; }; }
  PKGS="curl ca-certificates git unzip zip which procps-ng python3 fish zsh"
elif command -v pacman >/dev/null 2>&1; then
  # See first-run-smoke.sh: pacman needs --disable-sandbox under qemu emulation.
  PAC_FLAGS="--noconfirm --disable-sandbox"
  pacman -Sy $PAC_FLAGS >/tmp/pkg.log 2>&1 || { tail -5 /tmp/pkg.log; echo "pacman -Sy failed"; exit 1; }
  pkg_install() { pacman -S $PAC_FLAGS --needed "$@" >/tmp/pkg.log 2>&1 || { tail -5 /tmp/pkg.log; echo "pacman -S $* failed"; exit 1; }; }
  PKGS="curl ca-certificates git unzip zip which procps-ng python fish zsh"
else
  echo "no supported package manager"; exit 1
fi
# python3 is for the port squatter in case 5, and it is not optional: ubuntu:24.04
# ships without it, so the first run of case 5 silently failed to bind and
# reported "case not exercised" — a gap that reads like a result.
pkg_install $PKGS

# Fail here, loudly, rather than three cases later on a confusing symptom.
for t in curl git unzip zip which python3 fish zsh; do
  command -v "$t" >/dev/null 2>&1 \
    || { echo "setup: $t missing after installing: $PKGS"; exit 1; }
done

if command -v pacman >/dev/null 2>&1; then
  # Same exclusion as first-run-smoke.sh: install.sh:122 installs the JVM with a
  # plain `pacman -S`, which cannot sandbox in a container (seccomp under qemu,
  # Landlock on ARM-native Arch). Without this the setup install.sh dies and no
  # case runs at all. Pre-satisfying java lets every case below be exercised.
  echo "  NOTE: pre-installing the JVM; install.sh skips its pacman JVM step,"
  echo "        so that one step is NOT under test on this image."
  pkg_install jdk-openjdk
fi

# ── setup: install once ─────────────────────────────────────────────────────
echo "[setup] install.sh"
bash /repo/scripts/install.sh >/tmp/install1.log 2>&1 || {
  tail -20 /tmp/install1.log; echo "install.sh failed — cannot run the cases"; exit 1; }
cp -r /repo /work
echo "  ready"

new_project() {
  # $1 = project name. Runs the CLI from THIS checkout, like the smoke test.
  bash -ic "bb --config /work/bb.edn -e \"(require (quote wagoe.cli.main)) (wagoe.cli.main/-main \\\"new\\\" \\\"$1\\\")\"" 2>&1
}

# ── 1. install.sh re-run is idempotent ──────────────────────────────────────
# A newcomer who interrupts the installer, or who is not sure it finished, runs
# it again. That must converge, not accumulate.
head_ "[1] install.sh re-run (idempotency)"
RC_BEFORE=$(grep -c "babashka/bbin/bin" "$HOME/.bashrc" 2>/dev/null || echo 0)
bash /repo/scripts/install.sh >/tmp/install2.log 2>&1 || fail "second install.sh run exited non-zero"
RC_AFTER=$(grep -c "babashka/bbin/bin" "$HOME/.bashrc" 2>/dev/null || echo 0)
if [ "$RC_AFTER" -gt "$RC_BEFORE" ]; then
  fail "re-running install.sh appended another PATH line to ~/.bashrc ($RC_BEFORE -> $RC_AFTER); N runs leave N copies"
else
  ok "no duplicate PATH entry after a second run"
fi
for t in java clojure bb wagoe; do
  bash -ic "command -v $t" >/dev/null 2>&1 || fail "$t stopped resolving after the second run"
done
ok "toolchain still resolves"

# ── 2. project directory already exists ─────────────────────────────────────
# The destructive case: it must refuse, and it must not touch what is there.
head_ "[2] wagoe new into an existing directory"
cd /root
mkdir -p taken && echo "PRECIOUS" > taken/keep.txt
OUT="$(new_project taken || true)"
if [ "$(cat taken/keep.txt 2>/dev/null)" != "PRECIOUS" ]; then
  fail "existing file was modified or removed — data loss"
else
  ok "existing file left intact"
fi
if grep -qiE "already exists|not empty|choose a different|exists and is not empty" <<<"$OUT"; then
  ok "refuses with an actionable message"
else
  fail "no actionable message for an existing directory. Got: $(tail -3 <<<"$OUT")"
fi

# ── 3. invalid project names ────────────────────────────────────────────────
# These become a namespace and a directory. Whatever the rule is, the message
# has to state it — "Invalid name" alone leaves the user guessing.
head_ "[3] invalid project names"
for bad in "My Project" "123abc" "with/slash" ""; do
  label="${bad:-<empty>}"
  OUT="$(new_project "$bad" || true)"
  # "cannot be empty" belongs here: leaving it out made the empty-name case fall
  # through to the directory check below, where an empty name tests /root/ —
  # which always exists — and reported a product defect that was not there.
  if grep -qiE "invalid|cannot be empty|must (be|start|contain|match)|lowercase|letters|kebab|allowed|usage:" <<<"$OUT"; then
    ok "rejects \"$label\" with a stated rule"
  elif [ -n "$bad" ] && { [ -d "/root/$bad" ] || [ -d "/root/${bad// /}" ]; }; then
    fail "accepted \"$label\" and created a project directory from it"
  else
    fail "rejects \"$label\" without stating the rule. Got: $(tail -2 <<<"$OUT")"
  fi
done

# ── 4. two projects on one machine ──────────────────────────────────────────
# Both default to port 3000 and to a SQLite file. Generating the second must not
# corrupt the first, and the collision must be visible before it is confusing.
head_ "[4] a second project alongside the first"
cd /root
new_project alpha >/tmp/alpha.log 2>&1 || fail "first project failed to generate"
new_project beta  >/tmp/beta.log  2>&1 || fail "second project failed to generate"
if [ -d /root/alpha ] && [ -d /root/beta ]; then
  ok "both projects generated"
  # `|| true` on every one of these: under `set -e` a grep that matches nothing
  # aborts the whole run at the assignment. That killed cases 5 and 6 silently
  # on the first pass — the script exited mid-suite and still printed no summary.
  # The template writes `:port #or [#env HTTP_PORT 3000]`, not a bare number, so
  # a `:port <digits>` grep reports "unset" for a port that is very much set.
  # Read the default out of the #or form, and the auto-find window alongside it.
  # `.*`, not `[^\n]*`: POSIX bracket expressions do not read \n as newline, so
  # `[^\n]` excludes the literal characters backslash and "n" — and the value is
  # `#or [#env HTTP_PORT 3000]`, which contains an "n". The match failed and the
  # port read as "unset" on a port that was plainly set. grep is line-based, so
  # `.*` is what was meant anyway.
  port_of() { grep -oE ":port[[:space:]].*3[0-9]{3}" "$1" 2>/dev/null | grep -oE "3[0-9]{3}" | head -1 || true; }
  A_PORT=$(port_of /root/alpha/resources/conf/dev/config.edn)
  B_PORT=$(port_of /root/beta/resources/conf/dev/config.edn)
  RANGE=$(grep -oE ":port-range[^}]*\}" /root/alpha/resources/conf/dev/config.edn 2>/dev/null | head -1 || true)
  echo "       alpha port=${A_PORT:-unset} beta port=${B_PORT:-unset}"
  echo "       auto-find window: ${RANGE:-none declared}"
  if [ -n "$A_PORT" ] && [ "$A_PORT" = "$B_PORT" ]; then
    if [ -n "$RANGE" ]; then
      ok "both default to $A_PORT, but a port-range is declared for auto-find"
    else
      fail "both default to port $A_PORT with no auto-find range — the second app cannot boot"
    fi
  fi
  A_DB=$(grep -oE "\"[^\"]*\.db\"" /root/alpha/resources/conf/dev/config.edn 2>/dev/null | head -1 || true)
  B_DB=$(grep -oE "\"[^\"]*\.db\"" /root/beta/resources/conf/dev/config.edn 2>/dev/null  | head -1 || true)
  if [ -n "$A_DB" ] && [ "$A_DB" = "$B_DB" ]; then
    echo "       both use db file $A_DB (relative — fine while cwd differs)"
  fi
else
  fail "second project did not generate"
fi

# ── 5. port already in use ──────────────────────────────────────────────────
# The docs promise auto-find (3000 -> 3001..3099). Either it does that, or it
# says the port is taken. Silently failing to bind is the one unacceptable
# outcome, and it is what BOU-251 was.
#
# port_manager/suggest-port-strategy tests docker? BEFORE dev?, and Docker means
# exact-or-fail by design — auto-find would break an explicit -p mapping. So a
# containerised harness takes the Docker branch for every port test and never
# exercises the dev path a laptop user is on. Deleting /.dockerenv is what makes
# the laptop path reachable here; without it this case silently tests the wrong
# branch and reports on behaviour no newcomer will meet.
head_ "[5] boot with port 3000 already bound"
# Case 4 generates alpha, and `fail` only counts — it does not stop. So if that
# generation failed, an unguarded `cd /root/alpha` here trips set -e and kills
# the container mid-suite: cases 6 and 7 never run and no summary is printed,
# which reads as a crash rather than as one failed case. Same silent-abort shape
# that killed cases 5-6 earlier and that swallowed the Arch pacman error.
if [ ! -d /root/alpha ]; then
  echo "  SKIP — case 4 did not produce /root/alpha, so there is nothing to boot."
  SKIPPED=$((SKIPPED+1))
else
if [ -f /.dockerenv ]; then
  rm -f /.dockerenv
  echo "       removed /.dockerenv so the dev auto-find branch is the one under test"
fi
# /proc/1/comm must not read as a container init either, or docker? still wins.
echo "       pid1 comm: $(cat /proc/1/comm 2>/dev/null || echo unknown)"
cd /root/alpha
for d in /work/libs/*/; do
  name=$(basename "$d"); case "$name" in wagoe-*) art="$name" ;; *) art="wagoe-$name" ;; esac
  sed -E -i "s|com\.wagoe/${art}([[:space:]]+)\{:mvn/version \"[^\"]+\"\}|com.wagoe/${art}\1{:local/root \"${d%/}\"}|g" deps.edn bb.edn
done
python3 -c "
import socket,time
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
s.bind((\"0.0.0.0\",3000)); s.listen(1); time.sleep(600)
" >/dev/null 2>&1 &
SQUATTER=$!
sleep 1
if (echo > /dev/tcp/127.0.0.1/3000) 2>/dev/null; then
  ok "port 3000 is occupied by the test squatter"
  set -a; . ./.env 2>/dev/null || true; set +a
  export WAGOE_SCAFFOLDER_ROOT=/work/libs/scaffolder
  # `clojure -M:repl` only starts nREPL — the HTTP server binds inside (go).
  # Without this the app never attempted port 3000, and the case reported "no
  # message about the occupied port" about a bind that never happened.
  bash -ic "cd /root/alpha && set -a && . ./.env 2>/dev/null; set +a; timeout 300 clojure -M:repl" >/tmp/portboot.log 2>&1 &
  for _ in $(seq 1 90); do (echo > /dev/tcp/127.0.0.1/7888) 2>/dev/null && break; sleep 2; done
  if (echo > /dev/tcp/127.0.0.1/7888) 2>/dev/null; then
    bash -ic "clj-nrepl-eval -p 7888 \"(go)\"" >/tmp/portgo.log 2>&1 || true
    sleep 5
    SERVED=""
    for p in $(seq 3001 3010); do
      C=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://localhost:$p/api-docs/" || true)
      [ "$C" = "200" ] && { SERVED=$p; break; }
    done
    if [ -n "$SERVED" ]; then
      ok "auto-find moved the app to port $SERVED and it serves"
    elif grep -qiE "address already in use|port .*(in use|taken|unavailable)|trying port|using port 300[1-9]" /tmp/portboot.log /tmp/portgo.log; then
      ok "reports the occupied port instead of failing silently"
    else
      fail "port 3000 was taken: no auto-find, and no message naming the port. Tail: $(tail -3 /tmp/portgo.log | tr "\n" " ")"
    fi
  else
    fail "nREPL never came up — case not exercised"
  fi
  pkill -f "clojure -M:repl" 2>/dev/null || true
else
  fail "could not occupy port 3000 — case not exercised"
fi
kill $SQUATTER 2>/dev/null || true
fi  # /root/alpha exists

# ── 6. read-only working directory ──────────────────────────────────────────
# Generating into a directory you cannot write is a permissions message, not a
# stack trace.
head_ "[6] wagoe new into a read-only directory"
# Root ignores permission bits, so `chmod 500` restricts nothing when the
# container runs as uid 0 — the generator happily succeeded and this case used
# to SKIP, meaning it had never run anywhere. A read-only bind mount is enforced
# by the kernel for every uid, so /ro (mounted -v ...:ro by the host side of
# this script) is genuinely unwritable and the case can run as root.
#
# The chmod path is kept for a non-root run, where it is the more faithful
# reproduction of what a user hits.
if [ -d /ro ]; then
  cd /ro
  OUT="$(new_project blocked || true)"
  if grep -qiE "permission|read-only|cannot (write|create)|not writable" <<<"$OUT"; then
    ok "reports a permissions problem on a read-only mount"
  elif grep -qiE "Exception|at clojure\.|java\.io" <<<"$OUT"; then
    fail "surfaces a raw stack trace instead of a permissions message"
  else
    fail "unclear outcome. Got: $(tail -3 <<<"$OUT")"
  fi
elif [ "$(id -u)" -ne 0 ]; then
  mkdir -p ~/ro && chmod 500 ~/ro
  cd ~/ro
  OUT="$(new_project blocked || true)"
  if grep -qiE "permission|read-only|cannot (write|create)|not writable" <<<"$OUT"; then
    ok "reports a permissions problem"
  elif grep -qiE "Exception|at clojure\.|java\.io" <<<"$OUT"; then
    fail "surfaces a raw stack trace instead of a permissions message"
  else
    fail "unclear outcome. Got: $(tail -3 <<<"$OUT")"
  fi
  chmod 700 ~/ro
else
  echo "  SKIP — running as root with no read-only mount available."
  echo "         Not exercised, not passed."
  SKIPPED=$((SKIPPED+1))
fi

# ── 7. the PATH line lands where the user9s shell reads it ──────────────────
# The install can report success and still leave the toolchain unreachable, if
# the PATH line goes to a file the shell never loads. That is invisible to a
# bash-only test, which is why it survived until the shell matrix (BOU-261).
head_ "[7] PATH is set for the shell the user actually runs"
if command -v fish >/dev/null 2>&1; then
  rm -f "$HOME/.config/fish/config.fish"
  SHELL=$(command -v fish) bash /repo/scripts/install.sh >/tmp/install-fish.log 2>&1 \
    || fail "install.sh exited non-zero under fish"
  FISH_RC="$HOME/.config/fish/config.fish"
  if [ -f "$FISH_RC" ]; then
    ok "wrote $FISH_RC, the file fish reads"
  else
    fail "no fish config written; PATH line went somewhere fish never loads"
  fi
  # Syntax matters as much as location: `export PATH=...` is not fish.
  if grep -q "fish_add_path\|set -gx PATH" "$FISH_RC" 2>/dev/null; then
    ok "used fish syntax"
  else
    fail "wrote non-fish syntax into a fish config: $(head -1 "$FISH_RC" 2>/dev/null)"
  fi
  if fish -c "type -q wagoe" 2>/dev/null; then
    ok "wagoe resolves in a fresh fish session"
  else
    fail "wagoe still not on PATH in a fresh fish session"
  fi
  # Re-run under fish too — the idempotency guard has to hold per shell.
  BEFORE=$(grep -c "babashka/bbin/bin" "$FISH_RC" 2>/dev/null || echo 0)
  SHELL=$(command -v fish) bash /repo/scripts/install.sh >/dev/null 2>&1 || true
  AFTER=$(grep -c "babashka/bbin/bin" "$FISH_RC" 2>/dev/null || echo 0)
  [ "$AFTER" -gt "$BEFORE" ] \
    && fail "fish config gained a duplicate PATH line on re-run ($BEFORE -> $AFTER)" \
    || ok "no duplicate in the fish config on re-run"
else
  echo "  SKIP — fish not installed in this image."
  SKIPPED=$((SKIPPED+1))
fi

# ── 8. the same, for zsh ────────────────────────────────────────────────────
# zsh is the default shell on macOS and common on Linux, and it was verified by
# hand once during PR #353 and never again — only bash and fish were in the
# suite. A hand-verified case is one release away from being an unverified one.
head_ "[8] PATH is set for a zsh user"
if command -v zsh >/dev/null 2>&1; then
  rm -f "$HOME/.zshrc"
  SHELL=$(command -v zsh) bash /repo/scripts/install.sh >/tmp/install-zsh.log 2>&1 \
    || fail "install.sh exited non-zero under zsh"
  ZSH_RC="$HOME/.zshrc"
  if [ -f "$ZSH_RC" ]; then
    ok "wrote $ZSH_RC, the file zsh reads"
  else
    fail "no zsh config written; PATH line went somewhere zsh never loads"
  fi
  # ~/.zshrc takes POSIX export syntax, unlike the fish case above. Asserted so
  # a future rework of the shell branch cannot swap the two.
  if grep -q "export PATH" "$ZSH_RC" 2>/dev/null; then
    ok "used POSIX export syntax"
  else
    fail "wrote non-POSIX syntax into ~/.zshrc: $(head -1 "$ZSH_RC" 2>/dev/null)"
  fi
  if zsh -c "source $ZSH_RC >/dev/null 2>&1; command -v wagoe >/dev/null"; then
    ok "wagoe resolves in a zsh session that has read its rc"
  else
    fail "wagoe still not on PATH for zsh after sourcing $ZSH_RC"
  fi
  BEFORE=$(grep -c "babashka/bbin/bin" "$ZSH_RC" 2>/dev/null || echo 0)
  SHELL=$(command -v zsh) bash /repo/scripts/install.sh >/dev/null 2>&1 || true
  AFTER=$(grep -c "babashka/bbin/bin" "$ZSH_RC" 2>/dev/null || echo 0)
  [ "$AFTER" -gt "$BEFORE" ] \
    && fail "~/.zshrc gained a duplicate PATH line on re-run ($BEFORE -> $AFTER)" \
    || ok "no duplicate in ~/.zshrc on re-run"
else
  echo "  SKIP — zsh not installed in this image."
  SKIPPED=$((SKIPPED+1))
fi

echo
SUMMARY="$FAILED failed, $SKIPPED skipped"
if [ "$FAILED" -eq 0 ]; then
  echo "Adversarial cases: all exercised cases passed ($SUMMARY)."
  [ "$SKIPPED" -gt 0 ] && echo "Skipped cases are NOT coverage — see the notes above."
else
  echo "Adversarial cases: $SUMMARY — each failure is first-run friction."
  exit 1
fi
'
