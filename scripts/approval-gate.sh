#!/usr/bin/env bash
# approval-gate (BOU-1117): a pull request may merge when it changes only docs
# and tests, or when someone with write access labelled it `approved` after its
# head commit arrived. A label, not a review: agents open pull requests as the
# developer, who cannot approve their own.
#
# Env: GITHUB_REPOSITORY, PR (number), HEAD_SHA, EVENT (the pull_request
# action), GH_TOKEN.
set -euo pipefail
: "${GITHUB_REPOSITORY:?}" "${PR:?}" "${HEAD_SHA:?}"
repo=$GITHUB_REPOSITORY

# Docs and tests; changes/ holds changelog fragments (BOU-1118). Through
# ENVIRON: awk -v would read `\.` as `.`.
# - Not the files that steer agents.
# - No Markdown deeper than a library's root: resources ship to apps.
# - Not user.clj or data_readers: test/ is on the classpath, and these load on
#   every JVM start.
export LOW_RISK='^((docs|dev-docs|changes|test)/.+|libs/[^/]+/test/.+|(libs/[^/]+/)?[^/]+\.(md|adoc))$'
export RISKY='(^|/)(AGENTS|CLAUDE|CONTRIBUTING)\.md$|^dev-docs/agents/|(^|/)(user|data_readers)\.cljc?$'

# Tidy only: the freshness check below is what makes an old label stale, since
# a later event can cancel this run before it gets here.
if [ "${EVENT:-}" = synchronize ] &&
   ! out="$(gh api -X DELETE "repos/$repo/issues/$PR/labels/approved" 2>&1)"; then
  case "$out" in
    *"HTTP 404"*) ;;
    *) echo "approval-gate: could not remove the approved label: $out"; exit 1 ;;
  esac
fi

# A renamed file counts under both names. The API lists at most 3000 files; a
# longer list is not trusted.
files="$(gh api --paginate "repos/$repo/pulls/$PR/files" --jq '.[] | [.filename, .previous_filename // ""] | @tsv')"
total="$(gh api "repos/$repo/pulls/$PR" --jq .changed_files)"
listed="$(printf '%s' "$files" | grep -c . || true)"
risky="$(printf '%s\n' "$files" | tr '\t' '\n' |
  awk 'NF && (!($0 ~ ENVIRON["LOW_RISK"]) || $0 ~ ENVIRON["RISKY"])')"

if [ -z "$risky" ] && [ "$listed" = "$total" ]; then
  echo "approval-gate: docs and tests only; no approval needed."
  exit 0
fi

# The label counts when its latest `labeled` came after the head commit first
# reached GitHub (server times, both UTC ISO 8601, so they compare as strings).
# ponytail: a head reset to a commit pushed before the label still passes.
read -r labeller labelled_at < <(gh api --paginate "repos/$repo/issues/$PR/events" \
  --jq '.[] | select(.event == "labeled" and .label.name == "approved") | "\(.actor.login) \(.created_at)"' |
  tail -n 1) || true
arrived="$(gh api "repos/$repo/commits/$HEAD_SHA/check-suites" --jq '[.check_suites[].created_at] | min // ""')"
if [ -n "${labeller:-}" ] && [ -n "$arrived" ] && [[ "$labelled_at" > "$arrived" ]] &&
   gh api "repos/$repo/issues/$PR/labels" --jq '.[].name' | grep -qx approved; then
  case "$(gh api "repos/$repo/collaborators/$labeller/permission" --jq .role_name)" in
    admin | maintain | write)
      echo "approval-gate: approved by $labeller."
      exit 0 ;;
  esac
fi

echo "approval-gate: needs the 'approved' label, added after the last push by a developer with write access. It changes:"
printf '%s\n' "${risky:-(more files than the API lists)}" | sed 's/^/  /'
exit 1
