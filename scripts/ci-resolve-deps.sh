#!/usr/bin/env bash
# Resolve a deps.edn with retries, for the clojure-deps CI action.
#
#   scripts/ci-resolve-deps.sh <dir> [alias] [where]
#
# <dir> is resolved with `clojure -Sthreads 1 -P <alias>`; [where] only names it
# in messages. RESOLVE_RETRY_DELAY scales the backoff (seconds, default 10).
#
# Maven records a failed download in a `*.lastUpdated` marker beside the
# artifact and, for releases, reads the marker instead of the network on every
# later resolution — so a retry that leaves it in place fails the same way
# (BOU-440). Two kinds of marker are cleared before a retry:
#   - any marker recording a transfer error (a non-empty `.error=`);
#   - the markers of artifacts the last attempt reported as not found. A
#     transient 404 from Central records an empty `.error=`, exactly like a
#     genuine absence, so only the error message tells them apart (BOU-548).
# Other empty-`.error=` markers stay: every unpublished `-sources.jar` has one.
# A genuine 404 still fails: it is re-checked on each attempt and missed again.
set -uo pipefail

M2="${HOME}/.m2/repository"

clear_failed_downloads() {
  local cleared
  cleared=$(grep -rlE '\.error=.+' "$M2" --include='*.lastUpdated' 2>/dev/null || true)
  if [ -n "$cleared" ]; then
    echo "$cleared" | xargs rm -f
    echo "  cleared $(echo "$cleared" | wc -l | tr -d ' ') cached download failure(s)"
  fi
}

# Coordinates (g:a:ext[:classifier]:v) the log reports as not found. Covers the
# resolver's "Could not find artifact X", "X (absent)" and Maven's older
# "Failure to find X in".
missing_artifacts() {
  local coord='[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)?:[A-Za-z0-9_.+-]+'
  grep -oE "(Could not find artifact $coord|Failure to find $coord|$coord \(absent\))" "$1" 2>/dev/null \
    | grep -oE "$coord" | sort -u
}

clear_missing_markers() {
  local log="$1" coord g a v rest dir n
  for coord in $(missing_artifacts "$log"); do
    IFS=: read -r g a rest <<<"$coord"
    v="${coord##*:}"
    dir="$M2/${g//.//}/$a/$v"
    [ -d "$dir" ] || continue
    n=$(find "$dir" -maxdepth 1 -name "$a-$v*.lastUpdated" | wc -l | tr -d ' ')
    if [ "$n" -gt 0 ]; then
      find "$dir" -maxdepth 1 -name "$a-$v*.lastUpdated" -delete
      echo "  cleared cached 'not found' for $coord"
    fi
  done
}

resolve_with_retry() {
  local dir="$1" alias="${2:-}" where="${3:-$1}"
  local label="${alias:-base deps}" log attempt delay
  log=$(mktemp)
  for attempt in 1 2 3 4; do
    clear_failed_downloads
    [ "$attempt" -gt 1 ] && clear_missing_markers "$log"
    # $alias unquoted: it is empty for the base deps, and "" would be an arg.
    if (cd "$dir" && clojure -Sthreads 1 -P $alias) 2>&1 | tee "$log"; then
      rm -f "$log"
      return 0
    fi
    if [ "$attempt" -eq 4 ]; then
      echo "::error::dependency resolution failed for $label in $where after 4 attempts"
      rm -f "$log"
      return 1
    fi
    delay=$((attempt * attempt * ${RESOLVE_RETRY_DELAY:-10}))
    echo "::warning::resolution failed for $label in $where (attempt $attempt/4) — retrying in ${delay}s"
    sleep "$delay"
  done
}

resolve_with_retry "$@"
