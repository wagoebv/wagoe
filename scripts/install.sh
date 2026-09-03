#!/usr/bin/env bash
# Wagoe Framework installer
# Usage: curl -fsSL https://get.wagoe.org | bash
# Fallback: curl -fsSL https://raw.githubusercontent.com/wagoebv/wagoe/main/scripts/install.sh | bash

set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; DIM='\033[2m'; RESET='\033[0m'
ok()   { echo -e "${GREEN}✓${RESET} $1"; }
fail() { echo -e "${RED}✗${RESET} $1"; exit 1; }
info() { echo -e "${DIM}  $1${RESET}"; }

echo ""
echo "━━━ Wagoe Framework Installer ━━━━━━━━━━━━━━━━━━━━━"
echo ""

# ── Detect OS ────────────────────────────────────────────────
if [[ "$OSTYPE" == "darwin"* ]]; then
  OS="macos"
elif grep -qi microsoft /proc/version 2>/dev/null; then
  OS="wsl"
elif [[ -f /etc/debian_version ]]; then
  OS="debian"
elif [[ -f /etc/arch-release ]]; then
  OS="arch"
# Fedora and the RHEL family (RHEL, Rocky, Alma, CentOS Stream) all ship
# /etc/redhat-release and dnf. Nothing below is genuinely Debian-specific — the
# JVM comes from sdkman and the Clojure CLI, bb and bbin all use their own
# generic installers — so supporting these costs a detection branch and a hint.
elif [[ -f /etc/fedora-release || -f /etc/redhat-release ]]; then
  OS="fedora"
else
  fail "Unsupported OS. Wagoe supports macOS, Debian/Ubuntu, Fedora/RHEL, Arch, and WSL2.
  Windows users: install WSL2 first — https://learn.microsoft.com/en-us/windows/wsl/install"
fi
ok "Detected OS: $OS"

# ── Homebrew (macOS only) ─────────────────────────────────────
if [[ "$OS" == "macos" ]]; then
  if command -v brew &>/dev/null; then
    ok "Homebrew already installed"
  else
    info "Installing Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" \
      || fail "Failed to install Homebrew. Install it manually from https://brew.sh and re-run."
    ok "Homebrew installed"
  fi
fi

# ── System prerequisites ─────────────────────────────────────
# Check these BEFORE reaching for sdkman/brew. sdkman needs unzip (and zip for
# some operations); the CLI install below clones with git. On a bare Ubuntu
# image none of them are present, and without this check the first failure is
# sdkman's own "Please install unzip" — printed under a screenful of sdkman
# ASCII art, naming a tool the user never asked for and never naming Wagoe.
missing=()
# `which` is here because babashka's own installer calls it. Minimal Fedora and
# RHEL images do not ship it, and without this the run dies as
# "./install: line 155: which: command not found" — a third-party script's
# error about a tool the user never chose, which is the failure mode this whole
# check exists to prevent.
for tool in curl git unzip zip which; do
  command -v "$tool" &>/dev/null || missing+=("$tool")
done
if (( ${#missing[@]} > 0 )); then
  case "$OS" in
    macos)      hint="brew install ${missing[*]}" ;;
    debian|wsl) hint="sudo apt-get update && sudo apt-get install -y ${missing[*]}" ;;
    arch)       hint="sudo pacman -S --noconfirm ${missing[*]}" ;;
    fedora)     hint="sudo dnf install -y ${missing[*]}" ;;
    *)          hint="install them with your package manager" ;;
  esac
  fail "Missing required tool(s): ${missing[*]}

  Install them, then re-run this installer:
    $hint"
fi
ok "System prerequisites present"

# ── Privilege escalation ──────────────────────────────────────
# Run a command as root, but only escalate when we actually need to. Calling
# `sudo` unconditionally broke every containerised/minimal Linux install:
# images commonly run as root and ship no sudo at all, so the install steps
# died with "sudo: command not found".
as_root() {
  if [[ "$EUID" -eq 0 ]]; then
    "$@"
  elif command -v sudo &>/dev/null; then
    sudo "$@"
  else
    fail "Need root to run: $*

  Either re-run this installer as root, or install sudo first."
  fi
}

# ── JVM ──────────────────────────────────────────────────────
JAVA_MIN=21

# The major version of the java on PATH, or nothing.
#
# Two spellings: "21.0.5" since Java 9, and "1.8.0_402" before it, where the
# major is the second component. Both appear in the wild — the second is what a
# machine with a long-lived JDK 8 reports.
java_major() {
  local line raw major
  line=$(java -version 2>&1 | head -1) || return 1
  # Require the quoted form. Anything else — "command not found", a wrapper
  # printing its own banner — must read as "no usable java", not as a version
  # number parsed out of an error message.
  [[ "$line" =~ version\ \"([^\"]+)\" ]] || return 1
  raw="${BASH_REMATCH[1]}"
  case "$raw" in
    1.*) major=${raw#1.}; major=${major%%.*} ;;
    *)   major=${raw%%.*} ;;
  esac
  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  echo "$major"
}

JAVA_FOUND=""
command -v java &>/dev/null && JAVA_FOUND=$(java_major || true)

if [[ -n "$JAVA_FOUND" ]] && [[ "$JAVA_FOUND" -ge "$JAVA_MIN" ]] 2>/dev/null; then
  ok "JVM already installed (Java $JAVA_FOUND)"
else
  # A JDK older than $JAVA_MIN used to satisfy this check: the test was
  # `java -version | grep -q version`, which any JDK back to 8 passes. The
  # installer reported "JVM already installed" and carried on, and the failure
  # surfaced much later as a class-file-version error out of the Clojure
  # compiler — which tells a newcomer nothing about what to do.
  if [[ -n "$JAVA_FOUND" ]]; then
    info "Java $JAVA_FOUND is on PATH; Wagoe needs $JAVA_MIN or newer. Installing one..."
  fi
  info "Installing JVM..."
  if [[ "$OS" == "macos" ]]; then
    brew install --cask temurin 2>/dev/null || fail "Failed to install JVM via brew"
  elif [[ "$OS" == "debian" || "$OS" == "wsl" || "$OS" == "fedora" ]]; then
    if ! command -v sdk &>/dev/null; then
      info "Installing sdkman..."
      # Guarded and retried, unlike every other install step here, this one was
      # not: a transient 503 from sdkman.io aborted the whole installer showing
      # raw curl output and nothing from Wagoe (BOU-262). That is the failure
      # mode the prerequisite check above exists to prevent, one step later.
      #
      # -fsS, not -s: `-s` hides the server error too, so the one line that says
      # what went wrong is suppressed on the path where it matters most.
      sdkman_installed=false
      for attempt in 1 2 3; do
        if curl -fsSL "https://get.sdkman.io" | bash; then
          sdkman_installed=true
          break
        fi
        [[ $attempt -lt 3 ]] && {
          info "sdkman install failed (attempt $attempt/3) — retrying in $((attempt * 3))s..."
          sleep $((attempt * 3))
        }
      done
      [[ "$sdkman_installed" == true ]] || fail "Could not install sdkman after 3 attempts.

  sdkman.io provides the JVM for this platform, and it did not respond.
  This is usually temporary — check https://status.sdkman.io and re-run:
    curl -fsSL https://get.wagoe.org | bash

  Or install a JDK 21+ yourself and re-run; this installer skips the JVM
  step when java is already on PATH."
    fi
    # `set +u` is required, not defensive: sdkman-init.sh reads
    # SDKMAN_CANDIDATES_API unguarded, so sourcing it under our `set -u` aborts
    # with "unbound variable" immediately after sdkman prints "All done!" — the
    # installer dies right after reporting success. The `sdk` function itself is
    # not -u clean either, so the relaxation covers `sdk install` too.
    set +u
    # shellcheck disable=SC1090,SC1091
    source "$HOME/.sdkman/bin/sdkman-init.sh" \
      || fail "sdkman installed but its init script could not be sourced.
  Open a new terminal and re-run this installer."
    sdk install java || fail "Failed to install JVM via sdkman"
    set -u
  elif [[ "$OS" == "arch" ]]; then
    as_root pacman -S --noconfirm jdk-openjdk || fail "Failed to install JVM via pacman"
  fi

  # Verify rather than assume. Installing a new JDK does not remove the old one,
  # and whichever comes first on PATH is the one Clojure will use — so an
  # install that "succeeded" can leave the same too-old java in front.
  JAVA_NOW=$(java_major || true)
  if [[ -n "$JAVA_NOW" ]] && [[ "$JAVA_NOW" -ge "$JAVA_MIN" ]] 2>/dev/null; then
    ok "JVM installed (Java $JAVA_NOW)"
  elif [[ -n "$JAVA_NOW" ]]; then
    fail "Java $JAVA_NOW is still first on PATH, and Wagoe needs $JAVA_MIN or newer.

  A JDK $JAVA_MIN was installed, but the older one shadows it. Put the new JDK
  ahead of it on PATH — or remove the old one — and re-run:
    curl -fsSL https://get.wagoe.org | bash

  Check which one is winning with:
    java -version && command -v java"
  else
    fail "No java on PATH after installing a JVM.

  Open a new terminal and re-run; some installers only extend PATH for new
  shells. If that does not help, install a JDK $JAVA_MIN+ yourself and re-run —
  this installer skips the JVM step when a new enough java is already there."
  fi
fi

# ── Clojure CLI ───────────────────────────────────────────────
if command -v clojure &>/dev/null; then
  ok "Clojure CLI already installed"
else
  info "Installing Clojure CLI..."
  if [[ "$OS" == "macos" ]]; then
    brew install clojure 2>/dev/null || fail "Failed to install Clojure via brew"
  else
    curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
    chmod +x linux-install.sh
    # `|| fail` is load-bearing. This was `as_root ./… && rm …`, and `set -e`
    # exempts the failure of any command in an && list except the last, so a
    # failed install fell through to the ok "installed" line below and reported
    # success. Keep the install and the cleanup as separate statements.
    as_root ./linux-install.sh || fail "Failed to install the Clojure CLI"
    rm -f linux-install.sh
  fi
  ok "Clojure CLI installed"
fi

# ── Babashka ─────────────────────────────────────────────────
if command -v bb &>/dev/null; then
  ok "Babashka already installed"
else
  info "Installing Babashka..."
  if [[ "$OS" == "macos" ]]; then
    brew install borkdude/brew/babashka 2>/dev/null || fail "Failed to install Babashka via brew"
  else
    curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
    chmod +x install
    # Same && / set -e trap as the Clojure CLI step above — see the note there.
    as_root ./install || fail "Failed to install Babashka"
    rm -f install
  fi
  ok "Babashka installed"
fi

# ── bbin ─────────────────────────────────────────────────────
install_bbin() {
  bb -e "(babashka.deps/add-deps {:deps '{io.github.babashka/bbin {:git/url \"https://github.com/babashka/bbin\" :git/sha \"HEAD\"}}}) (require 'bbin.cli) (bbin.cli/install! \"bbin\")" 2>/dev/null \
    || { curl -fsSL https://raw.githubusercontent.com/babashka/bbin/master/bbin > /tmp/bbin && chmod +x /tmp/bbin && as_root mv /tmp/bbin /usr/local/bin/bbin; } \
    || fail "Failed to install bbin"
}

if ! command -v bbin &>/dev/null; then
  info "Installing bbin..."
  install_bbin
  ok "bbin installed"
elif ! bbin install --help 2>&1 | grep -q -- '--git/root'; then
  info "Upgrading bbin (current version does not support --git/root)..."
  install_bbin
  ok "bbin upgraded"
else
  ok "bbin already installed"
fi

# ── PATH ─────────────────────────────────────────────────────
BBIN_BIN="$HOME/.babashka/bbin/bin"

# Pick the file the user's shell actually reads, and the syntax it actually
# understands. Defaulting every non-bash shell to ~/.zshrc sent fish users'
# PATH line to a file fish never loads, in a syntax fish cannot parse: the
# install reported success and `wagoe` was still not found (BOU-261).
case "${SHELL:-}" in
  *fish*)
    SHELL_RC="$HOME/.config/fish/config.fish"
    PATH_LINE="fish_add_path \"$BBIN_BIN\""
    mkdir -p "$(dirname "$SHELL_RC")"
    ;;
  *bash*)
    SHELL_RC="$HOME/.bashrc"
    PATH_LINE="export PATH=\"$BBIN_BIN:\$PATH\""
    ;;
  *zsh*)
    SHELL_RC="$HOME/.zshrc"
    PATH_LINE="export PATH=\"$BBIN_BIN:\$PATH\""
    ;;
  *)
    # Unknown shell: ~/.profile is the widest-read POSIX location. Better a
    # file the shell probably reads than one it definitely does not.
    SHELL_RC="$HOME/.profile"
    PATH_LINE="export PATH=\"$BBIN_BIN:\$PATH\""
    ;;
esac

# Check the FILE, not just $PATH. The old guard tested the current process
# environment, which in a fresh non-interactive shell never has the entry — so
# every re-run appended another copy, and N runs left N lines (BOU-263).
if ! grep -qF "$BBIN_BIN" "$SHELL_RC" 2>/dev/null; then
  echo "$PATH_LINE" >> "$SHELL_RC"
  ok "Added $BBIN_BIN to PATH in $SHELL_RC"
  info "Run: source $SHELL_RC   (or open a new terminal)"
elif [[ ":$PATH:" != *":$BBIN_BIN:"* ]]; then
  ok "$SHELL_RC already sets the PATH entry"
  info "Run: source $SHELL_RC   (or open a new terminal)"
fi

export PATH="$BBIN_BIN:$PATH"

# ── wagoe CLI ──────────────────────────────────────────────
info "Fetching latest Wagoe release tag..."

# `curl -f` collapses every outcome into exit 22, so this used to blame the
# user's connection for a working one: GitHub allows 60 unauthenticated API
# requests per hour per IP, and a shared address — CI runner, office NAT, VPN
# exit — burns that between users. Read the status and the rate-limit headers
# instead, and tell them apart (BOU-410).
RELEASES_API="https://api.github.com/repos/wagoebv/wagoe/releases/latest"
TAG_HEADERS="$(mktemp)"
TAG_BODY="$(mktemp)"
trap 'rm -f "$TAG_HEADERS" "$TAG_BODY"' EXIT

# A token raises the limit to 5000/hour. CI has one; honouring it costs nothing
# and keeps the nightly matrix off the shared budget.
GH_AUTH_ARGS=()
GH_API_TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
if [[ -n "$GH_API_TOKEN" ]]; then
  GH_AUTH_ARGS=(-H "Authorization: Bearer $GH_API_TOKEN")
fi

set +e
HTTP_CODE="$(curl -sSL -D "$TAG_HEADERS" -o "$TAG_BODY" -w '%{http_code}' \
  ${GH_AUTH_ARGS[@]+"${GH_AUTH_ARGS[@]}"} "$RELEASES_API" 2>/dev/null)"
CURL_RC=$?
set -e

# curl itself failing — DNS, refused, timeout — is the only case that really is
# the connection.
if [[ $CURL_RC -ne 0 ]]; then
  fail "Could not reach $RELEASES_API (curl exit $CURL_RC). Check your internet connection."
fi

# `|| true`: an absent header is normal, and this runs under `set -e` where a
# grep miss inside an assignment would end the script instead of the branch.
header_value() { grep -i "^$1:" "$TAG_HEADERS" | tail -1 | tr -d '\r' | awk '{print $2}' || true; }

if [[ "$HTTP_CODE" == "403" || "$HTTP_CODE" == "429" ]]; then
  if [[ "$(header_value x-ratelimit-remaining)" == "0" ]]; then
    RESET_AT="$(header_value x-ratelimit-reset)"
    WAIT_MIN="?"
    if [[ "$RESET_AT" =~ ^[0-9]+$ ]]; then
      # Minutes from now, not a formatted timestamp: `date -d @epoch` is GNU and
      # `date -r epoch` is BSD, and this script runs on both.
      WAIT_MIN=$(( (RESET_AT - $(date +%s) + 59) / 60 ))
      [[ $WAIT_MIN -lt 1 ]] && WAIT_MIN=1
    fi
    fail "GitHub's API rate limit is used up for this IP address, so the release
  lookup was refused. Your connection is fine.
    Retry in ${WAIT_MIN} min, or raise the limit now by exporting a token:
    export GITHUB_TOKEN=<personal access token>   # 5000 requests/hour"
  fi
  fail "GitHub refused the release lookup with HTTP $HTTP_CODE.
    If you are behind a proxy that inspects HTTPS, that is the usual cause."
fi

if [[ "$HTTP_CODE" != "200" ]]; then
  fail "GitHub answered HTTP $HTTP_CODE for the release lookup at $RELEASES_API.
    Check https://www.githubstatus.com, then re-run this installer."
fi

# `|| true` is load-bearing under `set -o pipefail`: a body with no tag_name
# makes grep exit 1, which would abort the script before the message below.
WAGOE_TAG=$(grep '"tag_name"' "$TAG_BODY" \
  | sed 's/.*"tag_name": "\(.*\)".*/\1/' || true)

if [[ -z "$WAGOE_TAG" ]]; then
  fail "Could not determine latest Wagoe release tag: the API answered 200 but
    named no tag_name. Re-run, or install a specific tag by hand."
fi

info "Installing wagoe CLI @ $WAGOE_TAG..."
# bbin's git dep resolution (--deps-root + --config) does not reliably set up
# the classpath for monorepo sub-projects. Clone the repo and write a plain
# wrapper script with an explicit classpath instead.
WAGOE_CACHE="$HOME/.wagoe/releases/$WAGOE_TAG"
if [[ -d "$WAGOE_CACHE" ]]; then
  info "Using cached source at $WAGOE_CACHE"
else
  git clone --depth 1 --branch "$WAGOE_TAG" \
    https://github.com/wagoebv/wagoe.git \
    "$WAGOE_CACHE" 2>&1 | grep -v "^remote:" \
    || fail "Failed to clone Wagoe @ $WAGOE_TAG"
fi

mkdir -p "$BBIN_BIN"
cat > "$BBIN_BIN/wagoe" << EOF
#!/usr/bin/env bash
exec bb --classpath "$WAGOE_CACHE/libs/wagoe-cli/src:$WAGOE_CACHE/libs/wagoe-cli/resources" -m wagoe.cli.main "\$@"
EOF
chmod +x "$BBIN_BIN/wagoe"

hash -r 2>/dev/null || true
if ! command -v wagoe &>/dev/null; then
  fail "Failed to install wagoe CLI."
fi

ok "wagoe CLI installed"

# ── AI agent tooling ──────────────────────────────────────────
info "Installing AI agent tooling (clj-nrepl-eval + clj-paren-repair)..."
bbin install https://github.com/bhauman/clojure-mcp-light.git \
  --tag v0.2.2 --as clj-nrepl-eval \
  --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]' 2>/dev/null || true
bbin install https://github.com/bhauman/clojure-mcp-light.git \
  --tag v0.2.2 --as clj-paren-repair \
  --main-opts '["-m" "clojure-mcp-light.paren-repair"]' 2>/dev/null || true
ok "AI agent tooling installed"

echo ""
echo -e "${GREEN}━━━ Install complete ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""
echo "  Next step:"
echo ""
echo "    wagoe new <your-app-name>"
echo ""
echo "  AI tooling (REPL eval + paren repair):"
echo ""
echo "    clj-nrepl-eval --discover-ports"
echo ""
