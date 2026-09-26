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
  local out raw major
  # The whole output, not `head -1`: with JAVA_TOOL_OPTIONS or _JAVA_OPTIONS
  # set, every JVM prints "Picked up …" before its banner, so the first line
  # carries no version and a working JDK read as none at all (BOU-475).
  out=$(java -version 2>&1) || return 1
  # Require the quoted form. Anything else — "command not found", a wrapper
  # printing its own banner — must read as "no usable java", not as a version
  # number parsed out of an error message.
  [[ "$out" =~ version\ \"([^\"]+)\" ]] || return 1
  raw="${BASH_REMATCH[1]}"
  case "$raw" in
    1.*) major=${raw#1.}; major=${major%%.*} ;;
    *)   major=${raw%%.*} ;;
  esac
  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  echo "$major"
}

# Install sdkman, retrying a transient failure (BOU-262). A failed attempt can
# leave a half-written ~/.sdkman, and sdkman's installer refuses to run over an
# existing one — so every retry failed the same way (BOU-525). Only a directory
# this run created is removed; a user's own ~/.sdkman is left alone.
install_sdkman() {
  local init="$HOME/.sdkman/bin/sdkman-init.sh" preexisting=false attempt
  [[ -e "$HOME/.sdkman" ]] && preexisting=true
  for attempt in 1 2 3; do
    if curl -fsSL "https://get.sdkman.io" | bash && [[ -s "$init" ]]; then
      return 0
    fi
    [[ "$preexisting" == false ]] && rm -rf "$HOME/.sdkman"
    if [[ $attempt -lt 3 ]]; then
      info "sdkman install failed (attempt $attempt/3) — retrying in $((attempt * 3))s..."
      sleep $((attempt * 3))
    fi
  done
  return 1
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
    # Detected by its init script, not `command -v sdk`: `sdk` is a shell
    # function that exists only once that script is sourced, so an installed
    # sdkman looked absent and was installed again, which sdkman refuses.
    if [[ ! -s "$HOME/.sdkman/bin/sdkman-init.sh" ]]; then
      info "Installing sdkman..."
      install_sdkman || fail "Could not install sdkman after 3 attempts.

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

retry_fetch() {
  # Run "$@" up to 3 times with growing pauses. Every download here talks to a
  # host that rate-limits or 503s under load — sdkman.io, GitHub releases,
  # raw.githubusercontent — and one transient failure aborted an install that
  # would have worked on the next attempt (BOU-262 fixed that for sdkman alone;
  # BOU-417 for the rest). The last attempt's output is left to speak.
  local what="$1"; shift
  local attempt
  for attempt in 1 2 3; do
    if "$@"; then
      return 0
    fi
    if [[ $attempt -lt 3 ]]; then
      info "$what failed (attempt $attempt/3) — retrying in $((attempt * 3))s..."
      sleep $((attempt * 3))
    fi
  done
  return 1
}

# ── Clojure CLI ───────────────────────────────────────────────
if command -v clojure &>/dev/null; then
  ok "Clojure CLI already installed"
else
  info "Installing Clojure CLI..."
  if [[ "$OS" == "macos" ]]; then
    brew install clojure 2>/dev/null || fail "Failed to install Clojure via brew"
  else
    retry_fetch "Clojure CLI download" \
      curl -fsSL -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
      || fail "Could not download the Clojure CLI installer after 3 attempts."
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
    retry_fetch "Babashka download" \
      curl -fsSL -O https://raw.githubusercontent.com/babashka/babashka/master/install \
      || fail "Could not download the Babashka installer after 3 attempts."
    chmod +x install
    # Same && / set -e trap as the Clojure CLI step above — see the note there.
    as_root ./install || fail "Failed to install Babashka"
    rm -f install
  fi
  ok "Babashka installed"
fi

# ── bbin ─────────────────────────────────────────────────────
# Where a user's own executables go, and where bbin 0.2.x writes its scripts
# (XDG) — so on a fresh machine this is one directory rather than two.
USER_BIN="$HOME/.local/bin"

# Pinned, to a tag that exists. The install used to try
#   :git/sha "HEAD"
# which tools.deps refuses outright —
#   Library io.github.babashka/bbin has prefix sha, use full sha or add tag
# — so it could never succeed, and the fallback it dropped into moved the
# script into /usr/local/bin as root: a sudo password no piped install has a
# TTY to answer. The released `bbin` is a self-contained script with a `bb`
# shebang, so fetching it into a user directory needs no root and no classpath
# resolution at all (BOU-476).
BBIN_VERSION="v0.2.5"

install_bbin() {
  mkdir -p "$USER_BIN"
  retry_fetch "bbin download" \
    curl -fsSL -o "$USER_BIN/bbin" \
    "https://raw.githubusercontent.com/babashka/bbin/$BBIN_VERSION/bbin" \
    || fail "Could not download bbin $BBIN_VERSION after 3 attempts."
  chmod +x "$USER_BIN/bbin"
}

# Only whether bbin is there, with no version gate. There used to be one:
#
#   elif ! bbin install --help 2>&1 | grep -q -- '--git/root'
#
# which can never be satisfied. bbin has no per-command help — both
# `bbin install --help` and `bbin help install` print the top-level command
# list — so no flag name ever matches, and every machine that already had bbin
# took the upgrade branch, ran the install above and was asked for a sudo
# password. Nothing here needs `--git/root` in any case: the wagoe CLI is a
# wrapper written further down, and the two `bbin install` calls at the end use
# only --tag/--as/--main-opts, which every 0.2.x has (BOU-476).
BBIN_IN_USER_BIN=""
if command -v bbin &>/dev/null; then
  ok "bbin already installed"
elif [[ -x "$USER_BIN/bbin" ]]; then
  # On disk but not on PATH. This installer is usually run non-interactively
  # (`curl … | bash`), which never reads the rc file the PATH line went into —
  # so on a re-run `command -v` alone said "no bbin" and downloaded it again.
  export PATH="$USER_BIN:$PATH"
  hash -r 2>/dev/null || true
  BBIN_IN_USER_BIN="$USER_BIN"
  ok "bbin already installed"
else
  info "Installing bbin $BBIN_VERSION..."
  install_bbin
  BBIN_IN_USER_BIN="$USER_BIN"
  # It has to be callable before the next step, which asks bbin itself where it
  # writes scripts.
  export PATH="$USER_BIN:$PATH"
  hash -r 2>/dev/null || true
  command -v bbin &>/dev/null \
    || fail "Installed bbin into $USER_BIN but it is still not on PATH."
  ok "bbin installed"
fi

# ── PATH ─────────────────────────────────────────────────────
# Ask bbin where it writes scripts instead of assuming. This was hardcoded to
# ~/.babashka/bbin/bin, which 0.2.x deprecates in favour of ~/.local/bin — so on
# a fresh machine the wagoe wrapper and the PATH line went to a directory bbin
# does not use, while anything `bbin install` put down landed somewhere this
# script never added to PATH (BOU-476). Its warnings go to stderr and the path
# to stdout, so 2>/dev/null leaves just the path.
BBIN_BIN="$(bbin bin 2>/dev/null | tail -1)"
if [[ -z "$BBIN_BIN" ]]; then
  fail "\`bbin bin\` named no directory. Check \`bbin version\` and re-run."
fi

# Pick the file the user's shell actually reads, and the syntax it actually
# understands. Defaulting every non-bash shell to ~/.zshrc sent fish users'
# PATH line to a file fish never loads, in a syntax fish cannot parse: the
# install reported success and `wagoe` was still not found (BOU-261).
case "${SHELL:-}" in
  *fish*)
    SHELL_RC="$HOME/.config/fish/config.fish"
    path_line() { echo "fish_add_path \"$1\""; }
    mkdir -p "$(dirname "$SHELL_RC")"
    ;;
  *bash*)
    SHELL_RC="$HOME/.bashrc"
    path_line() { echo "export PATH=\"$1:\$PATH\""; }
    ;;
  *zsh*)
    SHELL_RC="$HOME/.zshrc"
    path_line() { echo "export PATH=\"$1:\$PATH\""; }
    ;;
  *)
    # Unknown shell: ~/.profile is the widest-read POSIX location. Better a
    # file the shell probably reads than one it definitely does not.
    SHELL_RC="$HOME/.profile"
    path_line() { echo "export PATH=\"$1:\$PATH\""; }
    ;;
esac

ensure_on_path() {
  local dir="$1"
  # Check the FILE, not just $PATH. The old guard tested the current process
  # environment, which in a fresh non-interactive shell never has the entry — so
  # every re-run appended another copy, and N runs left N lines (BOU-263).
  if ! grep -qF "$dir" "$SHELL_RC" 2>/dev/null; then
    path_line "$dir" >> "$SHELL_RC"
    ok "Added $dir to PATH in $SHELL_RC"
    info "Run: source $SHELL_RC   (or open a new terminal)"
  elif [[ ":$PATH:" != *":$dir:"* ]]; then
    ok "$SHELL_RC already sets the PATH entry for $dir"
    info "Run: source $SHELL_RC   (or open a new terminal)"
  fi
  if [[ ":$PATH:" != *":$dir:"* ]]; then
    export PATH="$dir:$PATH"
  fi
}

# bbin's script directory, and the directory holding bbin itself when that is a
# different one. They are the same on a fresh bbin and differ on an installation
# predating the XDG move.
PATH_DIRS=("$BBIN_BIN")
if [[ -n "$BBIN_IN_USER_BIN" && "$BBIN_IN_USER_BIN" != "$BBIN_BIN" ]]; then
  PATH_DIRS+=("$BBIN_IN_USER_BIN")
fi
for dir in "${PATH_DIRS[@]}"; do
  ensure_on_path "$dir"
done

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

# Retried, but only for what a retry can fix: a network blip or a 5xx. A 403
# with the rate limit exhausted resets in up to an hour, so sleeping 30 seconds
# and asking again just delays the message below by 30 seconds (BOU-417).
for attempt in 1 2 3; do
  set +e
  HTTP_CODE="$(curl -sSL -D "$TAG_HEADERS" -o "$TAG_BODY" -w '%{http_code}' \
    ${GH_AUTH_ARGS[@]+"${GH_AUTH_ARGS[@]}"} "$RELEASES_API" 2>/dev/null)"
  CURL_RC=$?
  set -e
  if [[ $CURL_RC -eq 0 && ! "$HTTP_CODE" =~ ^5 ]]; then
    break
  fi
  if [[ $attempt -lt 3 ]]; then
    info "Release lookup failed (attempt $attempt/3) — retrying in $((attempt * 3))s..."
    sleep $((attempt * 3))
  fi
done

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
