#!/usr/bin/env bash
# Push the current branch and wait for its GitHub Actions run.
# Prints PASS/FAIL; on failure prints the failure summary the workflow posts as a commit comment.
# Usage: scripts/ci.sh            (push + wait)
#        scripts/ci.sh --no-push  (just wait for HEAD's run)
set -euo pipefail
REPO="LoneWolfXVII/call2remind"
API="https://api.github.com/repos/$REPO"
AUTH=(-H "Authorization: Bearer ${GH_TOKEN:-${GITHUB_TOKEN:-}}" -H "Accept: application/vnd.github+json")
cd "$(git rev-parse --show-toplevel)"
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$BRANCH" == "main" ]]; then echo "Refusing to work on main"; exit 2; fi
if [[ "${1:-}" != "--no-push" ]]; then
  git push -q origin "$BRANCH" 2>&1 | grep -v -E "acknowledgments|push negotiation" || true
fi
SHA=$(git rev-parse HEAD)
echo "Waiting for CI on $BRANCH @ ${SHA:0:8}"
deadline=$((SECONDS + 3000))
run_id=""
while (( SECONDS < deadline )); do
  json=$(curl -sS "${AUTH[@]}" "$API/actions/runs?head_sha=$SHA&event=push&per_page=5")
  run_id=$(echo "$json" | python3 -c 'import json,sys; r=json.load(sys.stdin).get("workflow_runs",[]); print(r[0]["id"] if r else "")')
  if [[ -n "$run_id" ]]; then
    st=$(echo "$json" | python3 -c 'import json,sys; r=json.load(sys.stdin)["workflow_runs"][0]; print(r["status"], r["conclusion"])')
    set -- $st
    if [[ "$1" == "completed" ]]; then
      if [[ "${2:-}" == "success" ]]; then echo "PASS (run $run_id)"; exit 0; fi
      echo "FAIL: ${2:-unknown} (run $run_id)"
      sleep 5
      curl -sS "${AUTH[@]}" "$API/commits/$SHA/comments" | python3 -c 'import json,sys; c=json.load(sys.stdin); print(c[-1]["body"] if c else "(no failure summary posted; see run logs)")'
      exit 1
    fi
  fi
  sleep 20
done
echo "TIMEOUT waiting for CI (run ${run_id:-none})"; exit 3
