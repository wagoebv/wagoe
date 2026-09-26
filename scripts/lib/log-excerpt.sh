# Sourced by the smoke scripts; first-run-smoke.sh and wagoe-setup-skill-verify.sh
# hand it into their container with `declare -f`.
#
# Print a failing step's log: all of it when short, otherwise its first
# exception lines and then the tail. A tail alone can be nothing but stack
# frames, with the message that says what failed above it (BOU-550).
# Records the log in LOG_EXCERPT_SHOWN so fail() does not print it twice.
log_excerpt() {
  local log="$1" n="${2:-40}"
  # shellcheck disable=SC2034 # read by fail() in first-run-smoke.sh
  LOG_EXCERPT_SHOWN="$log"
  if [ "$(wc -l <"$log")" -le 150 ]; then
    echo "── $log"
    cat "$log"
  else
    echo "── first errors in $log"
    grep -m3 -nE "Exception|Error|Caused by" "$log" || echo "(none)"
    echo "── last $n lines of $log"
    tail -n "$n" "$log"
  fi
  echo "──"
}
