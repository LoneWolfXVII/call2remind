#!/usr/bin/env bash
# End-to-end tests on a booted emulator (CI job `instrumented`, inside android-emulator-runner).
#
# 1. The instrumentation suite:  ./gradlew :app:connectedDebugAndroidTest
#    (everything except the shell-driven ProcessDeathPhases).
# 2. Process death with a pending ring, which an in-process test cannot do to itself:
#    arm (instrumentation) -> process dead, screen off (shell) -> watch it ring (dumpsys)
#    -> verify (instrumentation reads the ring log).
#
# Writes e2e-out/ (logcat, gradle log, phase outputs, summary.txt). Exit 1 if anything failed.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=e2e-out
mkdir -p "$OUT"
: > "$OUT/summary.txt"
# Tells the workflow the emulator booted (a failure before this line is a boot failure, retried).
touch emulator-booted.marker

note() { echo "$*" | tee -a "$OUT/summary.txt"; }
sh_() { adb shell "$@" 2>&1 | tr -d '\r'; }

# Root shell (google_apis images): lets the tests send BOOT_COMPLETED to a non-exported receiver.
adb root >/dev/null 2>&1 || true
sleep 3
adb wait-for-device
for _ in $(seq 1 30); do [[ "$(sh_ getprop sys.boot_completed)" == "1" ]] && break; sleep 2; done
note "device: API $(sh_ getprop ro.build.version.sdk) $(sh_ getprop ro.product.model), shell: $(sh_ id | cut -d' ' -f1)"

# Screen may turn off only when a test says so; keep a real (swipe) keyguard.
sh_ svc power stayon false >/dev/null
sh_ settings put system screen_off_timeout 1800000 >/dev/null
sh_ locksettings set-disabled false >/dev/null
sh_ input keyevent KEYCODE_WAKEUP >/dev/null
sh_ wm dismiss-keyguard >/dev/null

adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null || true' EXIT

status=0

# --- 1. instrumentation suite ----------------------------------------------------------------
start=$SECONDS
timeout 1200 ./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=app.call2remind.e2e.ProcessDeathPhase \
  > "$OUT/connected.log" 2>&1
rc=$?
tail -n 40 "$OUT/connected.log"
note "connectedDebugAndroidTest: exit $rc in $((SECONDS - start))s"
(( rc != 0 )) && status=1

# --- 2. process death with a pending ring ----------------------------------------------------
start=$SECONDS
RUNNER=app.call2remind.test/androidx.test.runner.AndroidJUnitRunner
CLS=app.call2remind.e2e.ProcessDeathPhases

phase_ok() { # file -> 0 if the single test passed
  grep -q "^OK (1 test)" "$1" && ! grep -q "INSTRUMENTATION_STATUS_CODE: -[234]" "$1"
}

adb install -r -t app/build/outputs/apk/debug/app-debug.apk > "$OUT/install.log" 2>&1
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> "$OUT/install.log" 2>&1
sh_ input keyevent KEYCODE_WAKEUP >/dev/null
sh_ wm dismiss-keyguard >/dev/null

sh_ am instrument -w -r -e e2ePhase arm -e class "$CLS#arm" "$RUNNER" > "$OUT/phase-arm.txt"
if ! phase_ok "$OUT/phase-arm.txt"; then
  note "process death: arm phase FAILED"; tail -n 30 "$OUT/phase-arm.txt" | tee -a "$OUT/summary.txt"
  status=1
else
  state=$(sh_ run-as app.call2remind cat files/e2e_process_death.txt)
  fire_ms=$(echo "$state" | sed -n 2p)
  note "process death: armed $(echo "$state" | sed -n 1p) at ${fire_ms}"
  # The finished instrumentation killed the process (not a force-stop: alarms stay). Make sure.
  for _ in 1 2 3; do
    pid=$(sh_ pidof app.call2remind)
    [[ -z "$pid" ]] && break
    note "process death: app still alive (pid $pid), killing it"
    sh_ am kill app.call2remind >/dev/null
    sleep 1
    pid=$(sh_ pidof app.call2remind)
    [[ -n "$pid" ]] && { sh_ kill -9 "$pid" >/dev/null || sh_ run-as app.call2remind kill -9 "$pid" >/dev/null; }
    sleep 1
  done
  alarms=$(sh_ dumpsys alarm | grep -c "app.call2remind.action.ALARM_FIRE")
  note "process death: app pid='$(sh_ pidof app.call2remind)', alarm entries after death=$alarms"
  sh_ input keyevent KEYCODE_SLEEP >/dev/null
  observed_service=0; observed_screen=0
  deadline_ms=$(( fire_ms + 45000 ))
  while :; do
    now_ms=$(( $(sh_ date +%s) * 1000 ))
    (( now_ms > deadline_ms )) && break
    if (( now_ms >= fire_ms - 2000 )); then
      sh_ dumpsys activity services app.call2remind/.ringing.RingingService | grep -q "isForeground=true" && observed_service=1
      sh_ dumpsys activity activities | grep "ResumedActivity" | grep -q "IncomingCallActivity" && observed_screen=1
      (( observed_service && observed_screen )) && break
    fi
    sleep 2
  done
  note "process death: observed ringing service=$observed_service call screen=$observed_screen ($(( (now_ms - fire_ms) / 1000 ))s after fire time)"
  sh_ am instrument -w -r -e e2ePhase verify -e observedService "$observed_service" -e observedScreen "$observed_screen" \
    -e class "$CLS#verify" "$RUNNER" > "$OUT/phase-verify.txt"
  if phase_ok "$OUT/phase-verify.txt"; then
    note "process death: PASSED"
  else
    note "process death: verify phase FAILED"
    grep -E "Error|expected|but was|Exception|value of|FIRED" "$OUT/phase-verify.txt" | head -n 30 | tee -a "$OUT/summary.txt"
    status=1
  fi
fi
note "process death phases: $((SECONDS - start))s"

sleep 1
exit $status
