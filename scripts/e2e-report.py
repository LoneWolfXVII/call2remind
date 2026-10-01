#!/usr/bin/env python3
"""Summarizes an emulator end-to-end run (CI job `instrumented`).

Usage: e2e-report.py <title> notice   -> one `::notice` line per test outcome group (always run)
       e2e-report.py <title> failure  -> markdown failure summary (posted as a commit comment)

Inputs (all optional): build.log, emulator-booted.marker, e2e-out/{summary.txt,connected.log,
logcat.txt}, app/build/outputs/androidTest-results/connected/**/*.xml.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def read(path):
    return open(path, errors="replace").read().splitlines() if os.path.exists(path) else []


def results():
    passed, failed, skipped = [], [], []
    for f in glob.glob("app/build/outputs/androidTest-results/connected/**/*.xml", recursive=True):
        try:
            root = ET.parse(f).getroot()
        except Exception:
            continue
        for tc in root.iter("testcase"):
            name = f"{tc.get('classname', '').split('.')[-1]}.{tc.get('name')}"
            bad = next((tc.find(t) for t in ("failure", "error") if tc.find(t) is not None), None)
            if bad is not None:
                body = (bad.text or bad.get("message") or "").strip().splitlines()
                failed.append((name, body))
            elif tc.find("skipped") is not None:
                skipped.append(name)
            else:
                passed.append(name)
    return sorted(set(passed)), failed, sorted(set(skipped))


def e2e_lines(pattern):
    return [l.split(" C2R-E2E : ", 1)[-1] for l in read("e2e-out/logcat.txt") if "C2R-E2E" in l and re.search(pattern, l)]


def notice(title):
    passed, failed, skipped = results()
    reasons = {}
    for l in e2e_lines(r"SKIPPED "):
        m = re.match(r"SKIPPED (\S+)\(([\w.]+)\): (.*)", l)
        if m:
            reasons[f"{m.group(2).split('.')[-1]}.{m.group(1)}"] = m.group(3)
    msg = [f"passed {len(passed)}, failed {len(failed)}, skipped {len(skipped)}"]
    msg += [f"PASS {n}" for n in passed]
    msg += [f"FAIL {n}" for n, _ in failed]
    msg += [f"SKIP {n}: {reasons.get(n, '')}" for n in skipped]
    msg += [l for l in read("e2e-out/summary.txt") if l.startswith(("process death", "connected", "device"))]
    text = "%0A".join(m.replace("%", "%25").replace("\r", "").replace("\n", " ") for m in msg)
    print(f"::notice title=E2E {title}::{text}")


def failure(title):
    out = [f"## CI failure: {title}", ""]
    if not os.path.exists("emulator-booted.marker"):
        out.append("**Emulator did not boot** (no test ran).")
    errs = [l for l in read("build.log") if re.match(r"^(e: |w: .*error|> (?!Task )|FAILURE|\* What went wrong|Execution failed)", l)]
    if errs:
        out += ["### Build errors", "```"] + errs[:120] + ["```"]
    summary = read("e2e-out/summary.txt")
    if summary:
        out += ["### E2E summary", "```"] + summary[:80] + ["```"]
    passed, failed, skipped = results()
    out.append(f"### Instrumentation tests: {len(passed)} passed, {len(failed)} failed, {len(skipped)} skipped")
    for name, body in failed[:40]:
        out.append(f"- **{name}**\n  ```\n  " + "\n  ".join(body[:14]) + "\n  ```")
    notes = e2e_lines(r"(FAILED |SKIPPED |A11Y|PLATFORM|refused|still alive)")
    if notes:
        out += ["### Test notes (C2R-E2E)", "```"] + notes[:150] + ["```"]
    conn = read("e2e-out/connected.log")
    conn_errs = [l for l in conn if re.search(r"(FAILED|Process crashed|Test run failed|What went wrong|Exception|^> (?!Task ))", l)]
    if conn_errs:
        out += ["### connectedDebugAndroidTest log", "```"] + conn_errs[:60] + ["```"]
    log = read("e2e-out/logcat.txt")
    if log:
        keep = re.compile(r"(C2R-E2E|AndroidRuntime|FATAL|TestRunner.*(failed|finished)|RingingService|RingLauncher|AlarmScheduler|"
                          r"Receivers|SchedulingEngine|RingNotifications|SyncCoordinator|ActivityTaskManager.*call2remind|"
                          r"NotificationService.*call2remind|app\.call2remind.*(Exception|denied|refused|not allowed))")
        drop = re.compile(r"(WindowManager|CoreBackPreview|InputManager|C2R-E2E : (A11Y |alarm: ))")
        picked = [l[:400] for l in log if keep.search(l) and not drop.search(l)]
        for i in [i for i, l in enumerate(log) if "FATAL EXCEPTION" in l][:3]:
            picked += ["--- crash ---"] + log[i:i + 25]
        out += ["### Logcat excerpt", "```"] + picked[-200:] + ["```"]
    print("\n".join(out)[:60000])


if __name__ == "__main__":
    {"notice": notice, "failure": failure}[sys.argv[2]](sys.argv[1])
