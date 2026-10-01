#!/usr/bin/env bash
# Push the current branch and wait for its GitHub Actions run — ALL jobs (unit `build` and the
# emulator `instrumented (API n)` matrix). Prints each job's result as it finishes, then PASS or
# FAIL; on failure prints the failure summaries the jobs post as commit comments (one per failed
# job: compile errors, failing tests, lint; for emulator jobs also instrumentation failures and
# logcat excerpts).
# Usage: scripts/ci.sh                  (push + wait for every job)
#        scripts/ci.sh --no-push        (just wait for HEAD's run)
#        scripts/ci.sh [--no-push] --only build   (wait only for jobs whose name starts with "build")
set -euo pipefail
REPO="LoneWolfXVII/call2remind"
API="https://api.github.com/repos/$REPO"
AUTH=(-H "Authorization: Bearer ${GH_TOKEN:-${GITHUB_TOKEN:-}}" -H "Accept: application/vnd.github+json")
cd "$(git rev-parse --show-toplevel)"
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$BRANCH" == "main" ]]; then echo "Refusing to work on main"; exit 2; fi
PUSH=1
ONLY=""
while (( $# )); do
  case "$1" in
    --no-push) PUSH=0 ;;
    --only) ONLY="${2:-}"; shift ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
  shift
done
if (( PUSH )); then
  git push -q origin "$BRANCH" 2>&1 | grep -v -E "acknowledgments|push negotiation" || true
fi
SHA=$(git rev-parse HEAD)
echo "Waiting for CI on $BRANCH @ ${SHA:0:8}${ONLY:+ (jobs: $ONLY*)}"
deadline=$((SECONDS + 3600))
run_id=""
reported=""
while (( SECONDS < deadline )); do
  json=$(curl -sS "${AUTH[@]}" "$API/actions/runs?head_sha=$SHA&event=push&per_page=5")
  run_id=$(echo "$json" | python3 -c 'import json,sys; r=json.load(sys.stdin).get("workflow_runs",[]); print(r[0]["id"] if r else "")')
  if [[ -n "$run_id" ]]; then
    jobs=$(curl -sS "${AUTH[@]}" "$API/actions/runs/$run_id/jobs?per_page=50")
    run=$(echo "$json" | python3 -c 'import json,sys; r=json.load(sys.stdin)["workflow_runs"][0]; print(r["status"], r["conclusion"], r["created_at"])')
    # Report each job once when it completes; decide whether everything we wait for is done.
    verdict=$(ONLY="$ONLY" REPORTED="$reported" RUN="$run" python3 - "$jobs" <<'PY'
import json, os, sys
from datetime import datetime
only, reported = os.environ["ONLY"], set(filter(None, os.environ["REPORTED"].split("|")))
run_status = os.environ["RUN"].split()[0]
jobs = [j for j in json.loads(sys.argv[1]).get("jobs", []) if j["name"].startswith(only)]
def secs(j):
    try:
        f = "%Y-%m-%dT%H:%M:%SZ"
        return int((datetime.strptime(j["completed_at"], f) - datetime.strptime(j["started_at"], f)).total_seconds())
    except Exception:
        return 0
lines, done = [], []
for j in jobs:
    if j["status"] == "completed":
        done.append(j)
        if j["name"] not in reported:
            m, s = divmod(secs(j), 60)
            lines.append(f"  {j['name']}: {j['conclusion']} ({m}m{s:02d}s)")
            reported.add(j["name"])
all_done = bool(jobs) and len(done) == len(jobs) and (only or run_status == "completed")
ok = all_done and all(j["conclusion"] in ("success", "skipped") for j in done)
print("|".join(sorted(reported)))
print("done" if all_done else "wait", "ok" if ok else "bad")
print("\n".join(lines))
PY
)
    reported=$(echo "$verdict" | sed -n 1p)
    set -- $(echo "$verdict" | sed -n 2p)
    echo "$verdict" | sed -n '3,$p' | grep -v '^$' || true
    if [[ "$1" == "done" ]]; then
      # Per-job notices (the emulator jobs report every test's outcome this way).
      for jid in $(echo "$jobs" | python3 -c 'import json,sys; [print(j["id"]) for j in json.load(sys.stdin).get("jobs",[])]'); do
        curl -sS "${AUTH[@]}" "$API/check-runs/$jid/annotations" | python3 -c '
import json, sys
try:
    items = json.load(sys.stdin)
except Exception:
    items = []
for a in items if isinstance(items, list) else []:
    if a.get("annotation_level") == "notice" and (a.get("title") or "").startswith("E2E"):
        print("== " + a["title"]); print(a.get("message", ""))'
      done
      if [[ "$2" == "ok" ]]; then echo "PASS (run $run_id)"; exit 0; fi
      echo "FAIL (run $run_id)"
      sleep 5
      created=$(echo "$run" | awk '{print $3}')
      curl -sS "${AUTH[@]}" "$API/commits/$SHA/comments?per_page=100" | CREATED="$created" python3 -c '
import json, os, sys
c = [x for x in json.load(sys.stdin) if x["created_at"] >= os.environ["CREATED"]]
print("\n\n".join(x["body"] for x in c) if c else "(no failure summary posted; see run logs)")'
      exit 1
    fi
  fi
  sleep 20
done
echo "TIMEOUT waiting for CI (run ${run_id:-none})"; exit 3
