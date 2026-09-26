# Sourced by scripts/first-run-smoke.sh and scripts/wagoe-setup-skill-verify.sh,
# which hand it into their container with `declare -f`.
#
# Print the section of each step quickstart reports as failed, or the whole log
# when it is short. A failed step is early in the log, so the tail fail() prints
# never reached its error (BOU-545). Returns 1 when no step failed.
quickstart_failed_steps() {
  local log="$1" n from to
  grep -q "failed step(s)" "$log" || return 1
  if [ "$(wc -l <"$log")" -le 150 ]; then cat "$log"; return 0; fi
  for n in $(grep "failed step(s)" "$log" | grep -oE "\[[0-9]+/8\]" | tr -d "[]" | cut -d/ -f1); do
    from=$(grep -m1 -nF "[$n/8]" "$log" | cut -d: -f1)
    to=$(tail -n +"$((from + 1))" "$log" | grep -m1 -nE "\[[0-9]+/8\]" | cut -d: -f1 || true)
    echo "── step [$n/8] of $log"
    if [ -n "$to" ]; then sed -n "${from},$((from + to - 1))p" "$log"; else tail -n +"$from" "$log"; fi
  done
}
