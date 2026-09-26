#!/usr/bin/env bash
# Resolve a deps.edn with retries, for the clojure-deps CI action.
#
#   scripts/ci-resolve-deps.sh <dir> [alias] [where]
#
# <dir> is resolved with `clojure -Sthreads 1 -P <alias>`; [where] only names it
# in messages. RESOLVE_RETRY_DELAY scales the backoff (seconds, default 10).
#
# Only waiting helps. tools.deps neither writes nor reads Maven's `*.lastUpdated`
# markers for a failed resolution (checked with CLI 1.12.6.1673), so each attempt
# goes back to the network (BOU-548). The waits add up to five minutes: a Central
# outage that failed four attempts spanned two and a half.
set -uo pipefail

ATTEMPTS=5

resolve_with_retry() {
  local dir="$1" alias="${2:-}" where="${3:-$1}"
  local label="${alias:-base deps}" attempt delay status
  for attempt in $(seq 1 "$ATTEMPTS"); do
    # $alias unquoted: it is empty for the base deps, and "" would be an arg.
    (cd "$dir" && clojure -Sthreads 1 -P $alias) 2>&1
    status=$?
    [ "$status" -eq 0 ] && return 0
    if [ "$attempt" -eq "$ATTEMPTS" ]; then
      echo "::error::dependency resolution failed for $label in $where after $ATTEMPTS attempts"
      return "$status"
    fi
    delay=$((attempt * attempt * ${RESOLVE_RETRY_DELAY:-10}))
    echo "::warning::resolution failed for $label in $where (attempt $attempt/$ATTEMPTS) — retrying in ${delay}s"
    sleep "$delay"
  done
}

resolve_with_retry "$@"
