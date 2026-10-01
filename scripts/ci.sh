#!/usr/bin/env bash
# Push the current branch and wait for its GitHub Actions run — ALL jobs (unit `build` and the
# emulator `instrumented (API n)` matrix). Prints each job's result as it finishes, the emulator
# jobs' per-test results, then PASS or FAIL; on failure prints the failure summaries the jobs
# post as commit comments (one per failed job: compile errors, failing tests, lint; for emulator
# jobs also instrumentation failures and logcat excerpts).
#
# A job cancelled because a newer push superseded it is followed into the newest run of the
# branch (whose commit contains this one: the branch is only ever rebased and fast-forwarded).
#
# Usage: scripts/ci.sh                          (push + wait for every job)
#        scripts/ci.sh --no-push                (just wait for HEAD's run)
#        scripts/ci.sh [--no-push] --only build (only jobs whose name starts with "build")
set -euo pipefail
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
export SHA BRANCH ONLY
SHA=$(git rev-parse HEAD)
echo "Waiting for CI on $BRANCH @ ${SHA:0:8}${ONLY:+ (jobs: $ONLY*)}"
exec python3 - <<'PY'
import json, os, sys, time, urllib.request

REPO = "LoneWolfXVII/call2remind"
API = f"https://api.github.com/repos/{REPO}"
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""
SHA, BRANCH, ONLY = os.environ["SHA"], os.environ["BRANCH"], os.environ["ONLY"]
DEADLINE = time.time() + 3 * 3600


def get(path):
    req = urllib.request.Request(API + path, headers={"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:  # transient API / proxy errors
            if attempt == 4:
                raise
            time.sleep(5 * (attempt + 1))


def run_for_sha(sha):
    runs = get(f"/actions/runs?head_sha={sha}&event=push&per_page=5").get("workflow_runs", [])
    return runs[0] if runs else None


def newest_run_after(run):
    runs = get(f"/actions/runs?branch={BRANCH}&event=push&per_page=10").get("workflow_runs", [])
    newer = [r for r in runs if r["created_at"] > run["created_at"]]
    return max(newer, key=lambda r: r["created_at"]) if newer else None


def jobs_of(run):
    return [j for j in get(f"/actions/runs/{run['id']}/jobs?per_page=50").get("jobs", []) if j["name"].startswith(ONLY)]


def secs(j):
    from datetime import datetime
    f = "%Y-%m-%dT%H:%M:%SZ"
    try:
        return int((datetime.strptime(j["completed_at"], f) - datetime.strptime(j["started_at"], f)).total_seconds())
    except Exception:
        return 0


def notices(job):
    try:
        items = get(f"/check-runs/{job['id']}/annotations")
    except Exception:
        return
    for a in items if isinstance(items, list) else []:
        if a.get("annotation_level") == "notice" and (a.get("title") or "").startswith("E2E"):
            print("== " + a["title"])
            print(a.get("message", ""))


run = None
while run is None and time.time() < DEADLINE:
    run = run_for_sha(SHA)
    if run is None:
        time.sleep(20)
if run is None:
    print("TIMEOUT: no run for this commit")
    sys.exit(3)

final = {}       # job name -> completed job
runs_used = {}   # job name -> run it finished in
waiting = None   # names still to resolve (None = every job of the first run)
reported = set()
while time.time() < DEADLINE:
    jobs = jobs_of(run)
    if waiting is not None:
        jobs = [j for j in jobs if j["name"] in waiting]
    for j in jobs:
        if j["status"] == "completed" and (run["id"], j["name"]) not in reported:
            reported.add((run["id"], j["name"]))
            m, s = divmod(secs(j), 60)
            note = "" if run["head_sha"] == SHA else f" [run {run['id']} @ {run['head_sha'][:8]}]"
            print(f"  {j['name']}: {j['conclusion']} ({m}m{s:02d}s){note}", flush=True)
            final[j["name"]] = j
            runs_used[j["name"]] = run
    names = {j["name"] for j in jobs}
    status = get(f"/actions/runs/{run['id']}")["status"]
    if jobs and all(j["status"] == "completed" for j in jobs) and (ONLY or waiting is not None or status == "completed"):
        superseded = [n for n in names if final[n]["conclusion"] == "cancelled"]
        if superseded:
            newer = newest_run_after(run)
            if newer is not None:
                print(f"  superseded: {', '.join(sorted(superseded))} -> following run {newer['id']} @ {newer['head_sha'][:8]}", flush=True)
                run, waiting = newer, set(superseded)
                time.sleep(20)
                continue
        break
    time.sleep(20)
else:
    print("TIMEOUT waiting for CI")
    sys.exit(3)

for name in sorted(final):
    notices(final[name])
bad = {n: j for n, j in final.items() if j["conclusion"] not in ("success", "skipped")}
if not bad:
    print(f"PASS (run {run['id']})")
    sys.exit(0)
print("FAIL: " + ", ".join(f"{n}={j['conclusion']}" for n, j in sorted(bad.items())))
time.sleep(5)
shown = set()
for n in sorted(bad):
    r = runs_used[n]
    if r["id"] in shown:
        continue
    shown.add(r["id"])
    comments = [c for c in get(f"/commits/{r['head_sha']}/comments?per_page=100") if c["created_at"] >= r["created_at"]]
    print("\n\n".join(c["body"] for c in comments) if comments else f"(no failure summary posted for run {r['id']}; see run logs)")
sys.exit(1)
PY
