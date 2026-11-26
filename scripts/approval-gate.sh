#!/usr/bin/env bash
# approval-gate (BOU-1117): a pull request may merge when it changes only docs
# and tests, or when someone with write access labelled it `approved`. A label,
# not a review: agents open pull requests as the developer, who cannot approve
# their own. A new push removes the label, so it always approves the head.
#
# Env: GITHUB_REPOSITORY, PR (number), EVENT (the pull_request action), GH_TOKEN.
set -euo pipefail
: "${GITHUB_REPOSITORY:?}" "${PR:?}"
repo=$GITHUB_REPOSITORY

# Docs and tests. Not AGENTS.md or CLAUDE.md, which steer the agents, and no
# Markdown deeper than a library's root: resources ship to apps.
low_risk='^((docs|dev-docs|changes|test)/.+|libs/[^/]+/test/.+|(libs/[^/]+/)?[^/]+\.(md|adoc))$'
agent_docs='(^|/)(AGENTS|CLAUDE)\.md$'

if [ "${EVENT:-}" = synchronize ]; then
  gh api -X DELETE "repos/$repo/issues/$PR/labels/approved" >/dev/null 2>&1 || true
fi

# A renamed file counts under both names. The API lists at most 3000 files; a
# longer list is not trusted.
files="$(gh api --paginate "repos/$repo/pulls/$PR/files" --jq '.[] | [.filename, .previous_filename // ""] | @tsv')"
total="$(gh api "repos/$repo/pulls/$PR" --jq .changed_files)"
listed="$(printf '%s' "$files" | grep -c . || true)"
risky="$(printf '%s\n' "$files" | tr '\t' '\n' |
  awk -v ok="$low_risk" -v agent="$agent_docs" 'NF && (!($0 ~ ok) || $0 ~ agent)')"

if [ -z "$risky" ] && [ "$listed" = "$total" ]; then
  echo "approval-gate: docs and tests only; no approval needed."
  exit 0
fi

labeller="$(gh api --paginate "repos/$repo/issues/$PR/events" \
  --jq '.[] | select(.event == "labeled" and .label.name == "approved") | .actor.login' | tail -n 1)"
if [ -n "$labeller" ] &&
   gh api "repos/$repo/issues/$PR/labels" --jq '.[].name' | grep -qx approved; then
  case "$(gh api "repos/$repo/collaborators/$labeller/permission" --jq .permission)" in
    admin | maintain | write)
      echo "approval-gate: approved by $labeller."
      exit 0 ;;
  esac
fi

echo "approval-gate: needs the 'approved' label from a developer with write access. It changes:"
printf '%s\n' "${risky:-(more files than the API lists)}" | sed 's/^/  /'
exit 1
