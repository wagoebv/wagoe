#!/usr/bin/env bash
# Keeps one open issue for a failing run: opened, or commented on, when it
# fails; closed by the next one that passes. A red nightly used to show only in
# the Actions tab, and nobody looked (BOU-290). A red main uses it too (BOU-1026).
#
# Usage: failure-issue.sh fail|pass
# Env:   GH_TOKEN, GITHUB_REPOSITORY, RUN_ID, RUN_URL
#        FAILURE_LABEL, FAILURE_TITLE, FAILURE_WHAT (default: the nightly matrix)
#        FAILURE_NOTIFY: a login to mention on each failure report
set -euo pipefail

LABEL="${FAILURE_LABEL:-nightly-failure}"
TITLE="${FAILURE_TITLE:-Nightly first-run matrix is failing}"
WHAT="${FAILURE_WHAT:-The scheduled first-run matrix failed.}"
REPO="${GITHUB_REPOSITORY:?}"
: "${RUN_ID:?}" "${RUN_URL:?}"

open_issue() {
  gh issue list --repo "$REPO" --label "$LABEL" --state open \
    --json number --jq '.[0].number // empty'
}

failed_jobs() {
  gh run view "$RUN_ID" --repo "$REPO" --json jobs \
    --jq '.jobs[] | select(.conclusion == "failure" or .conclusion == "cancelled") | "- " + .name'
}

# The failed-job set each report recorded, newest last. The released-tag cells
# stay red until a release, so a night with the same set is not news.
last_reported_set() {
  gh issue view "$1" --repo "$REPO" --json body,comments \
    --jq '[.body] + [.comments[].body] | map(capture("<!-- failed-jobs: (?<j>.*) -->").j) | last // ""'
}

case "${1:-}" in
  fail)
    number="$(open_issue)"
    jobs="$(failed_jobs || echo "- (could not list jobs; see the run)")"
    set_key="$(printf '%s\n' "$jobs" | sort | paste -sd ';' -)"
    report="Run: $RUN_URL${FAILURE_NOTIFY:+ (cc @$FAILURE_NOTIFY)}

Failed jobs:
$jobs

<!-- failed-jobs: $set_key -->"
    if [[ -n "$number" ]]; then
      if [[ "$(last_reported_set "$number")" == "$set_key" ]]; then
        echo "Same failed jobs as last reported on #$number; not commenting"
        exit 0
      fi
      gh issue comment "$number" --repo "$REPO" --body "Failing differently now. $report"
      echo "Commented on #$number"
    else
      gh label create "$LABEL" --repo "$REPO" --color d73a4a --force \
        --description "$TITLE"
      gh issue create --repo "$REPO" --title "$TITLE" --label "$LABEL" \
        --body "$WHAT This issue is reused on later failures and closed by the next passing run.

$report"
    fi
    ;;
  pass)
    number="$(open_issue)"
    if [[ -n "$number" ]]; then
      gh issue close "$number" --repo "$REPO" --comment "Passing again: $RUN_URL"
      echo "Closed #$number"
    fi
    ;;
  *)
    echo "usage: $0 fail|pass" >&2
    exit 2
    ;;
esac
