#!/usr/bin/env bash
# approval-gate (BOU-1117): a pull request may merge when it changes only docs
# and tests, or when someone with write access labelled it `approved` after
# its current head and base. A label, not a review: agents open pull requests
# as the developer, who cannot approve their own.
#
# Env: GITHUB_REPOSITORY, PR (number), HEAD_SHA, HEAD_REF, HEAD_REPO (owner/name),
# EVENT (the pull_request action), BASE_CHANGED (true on a retarget), GH_TOKEN.
set -euo pipefail
: "${GITHUB_REPOSITORY:?}" "${PR:?}" "${HEAD_SHA:?}" "${HEAD_REF:?}" "${HEAD_REPO:?}"
export HEAD_REF HEAD_REPO
repo=$GITHUB_REPOSITORY

# Docs, tests and test data; changes/ holds changelog fragments (BOU-1118).
# Other code under a test dir needs approval: test/ comes before src on the
# classpath, so it can shadow a namespace or the logging config. Markdown only
# at the root or a library's root: resources ship to apps. Through ENVIRON, as
# awk -v would read `\.` as `.`.
export LOW_RISK='^((docs|dev-docs|changes)/.+|(libs/[^/]+/)?test/.+(_test\.clj[cs]?|\.(edn|json|txt))|(libs/[^/]+/)?[^/]+\.(md|adoc))$'
export RISKY='(^|/)(AGENTS|CLAUDE|CONTRIBUTING)\.md$|^dev-docs/agents/'

# Tidy only: the freshness check below is what makes an old label stale, since
# a later event can cancel this run before it gets here.
if [ "${EVENT:-}" = synchronize ] || [ "${BASE_CHANGED:-}" = true ]; then
  if ! out="$(gh api -X DELETE "repos/$repo/issues/$PR/labels/approved" 2>&1)"; then
    case "$out" in
      *"HTTP 404"*) ;;
      *) echo "approval-gate: could not remove the approved label: $out"; exit 1 ;;
    esac
  fi
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

# The label counts when its latest `labeled` is newer than the base's last
# retarget and than the head: ci.yml starts a run for every new head, so its
# latest run for this head on this branch is when the head arrived. Server
# times in UTC ISO 8601 compare as strings. No run yet: not approved.
events="$(gh api --paginate "repos/$repo/issues/$PR/events" \
  --jq '.[] | select((.event == "labeled" and .label.name == "approved") or .event == "base_ref_changed") | "\(.event) \(.created_at) \(.actor.login)"')"
read -r _ labelled_at labeller < <(grep '^labeled ' <<<"$events" | tail -n 1) || true
retargeted="$( (grep '^base_ref_changed ' <<<"$events" || true) | tail -n 1 | cut -d' ' -f2)"
arrived="$(gh api "repos/$repo/actions/workflows/ci.yml/runs?event=pull_request&head_sha=$HEAD_SHA" \
  --jq '[.workflow_runs[] | select(.head_branch == env.HEAD_REF and .head_repository.full_name == env.HEAD_REPO) | .created_at] | max // ""')"

if [ -n "${labeller:-}" ] && [ -n "$arrived" ] && [[ "$labelled_at" > "$arrived" ]] &&
   [[ "$labelled_at" > "$retargeted" ]] &&
   gh api "repos/$repo/issues/$PR/labels" --jq '.[].name' | grep -qx approved; then
  case "$(gh api "repos/$repo/collaborators/$labeller/permission" --jq .role_name)" in
    admin | maintain | write)
      echo "approval-gate: approved by $labeller."
      exit 0 ;;
  esac
fi

echo "approval-gate: needs the 'approved' label, added after the last push and retarget by a developer with write access. It changes:"
printf '%s\n' "${risky:-(more files than the API lists)}" | sed 's/^/  /'
exit 1
