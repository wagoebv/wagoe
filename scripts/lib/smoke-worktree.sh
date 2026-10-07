# Worktree mode of scripts/first-run-smoke.sh: generated projects use the
# checkout copied to /work, not the published artifacts. Sourced in the
# container by the smoke and by scripts/lib/wagoe-worktree.

# Point every com.wagoe dep in the project at $1 to /work, then fail if one is
# still pinned to a published version.
#
# Every dep, not a couple: the first version rewrote platform and tools, and
# the run still exercised the *published* scaffolder, so a fixed bug appeared
# unfixed. Artifact id maps to a directory under libs/: wagoe-core -> libs/core,
# but wagoe-cli -> libs/wagoe-cli. A pin neither name matches is left alone, and
# the check after the loop reports it.
pin_to_worktree() {
  local dir="$1" d name art f still
  for d in /work/libs/*/; do
    name=$(basename "$d")
    case "$name" in
      wagoe-*) art="$name" ;;
      *)       art="wagoe-$name" ;;
    esac
    sed -E -i "s|com\.wagoe/${art}([[:space:]]+)\{:mvn/version \"[^\"]+\"\}|com.wagoe/${art}\1{:local/root \"${d%/}\"}|g" \
      "$dir/deps.edn" "$dir/bb.edn"
  done
  for f in deps.edn bb.edn; do
    still=$(grep -oE "com\.wagoe/[a-z0-9-]+[[:space:]]*\{:mvn/version" "$dir/$f" || true)
    if [ -n "$still" ]; then
      echo "$dir/$f still pins published com.wagoe artifacts, so this run would test the release:" >&2
      echo "$still" >&2
      return 1
    fi
  done
}
