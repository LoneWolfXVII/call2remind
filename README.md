# Call2Remind

An Android app that delivers your reminders as **incoming phone calls**: a full-screen ringing
call over the lock screen instead of a notification you swipe away. Reminders come from the
places you already keep them, with no re-entry:

- calendar events (any synced account, all-day events at a per-source default time),
- Google Tasks and Microsoft To Do (cloud),
- Samsung Reminders (notification listener, opt-in, best effort),
- contact birthdays,
- habits created in the app (days of week + times).

Answer to hear the reminder read aloud (TTS), then mark it Done or Snooze it. Decline snoozes
(5 min by default) up to three ring-backs, after which it lands in Missed. Everything runs
on-device; there is no server and no analytics.

Product and engineering decisions live in [`docs/SPEC.md`](docs/SPEC.md) (edge-case table
included); screen designs are in [`docs/design`](docs/design).

## Architecture

Two Gradle modules:

| Module | What it holds |
|---|---|
| `:core` | Pure Kotlin (JVM 17), no Android. Domain models (`Reminder`, `Schedule`, `Occurrence`), recurrence, `ScheduleExpander` / `OccurrencePlanner` (rolling 48 h window, dedupe by `source + external id + planned instant`), the occurrence state machine, snooze / recovery policy, ring queue, `RingDecision` (how to present a ring), default times, `DeviceClock` / `DeviceZonePolicy` (time zones), speech text. Heavily unit-tested. |
| `:app` | Android (`app.call2remind`): Hilt, Room (device-protected storage, so rings work before first unlock), WorkManager, DataStore, Compose UI. Android-facing pieces sit behind interfaces (`AlarmScheduler`, `RingAlerts`, `RingContextProvider`, ...) with fakes in tests. |

### Ring pipeline

```
sources ──sync──▶ Room (reminders) ──SchedulingEngine.replan──▶ Room (occurrences)
                                                        │
                                       AlarmManager (setAlarmClock / exact)
                                                        │ fires
                                                        ▼
            AlarmReceiver ── claimNext (DB ring lock, queue) ──▶ RingingService (FGS, specialUse)
                                                        │
              RingDecision ─┬─ full-screen CallStyle notification ──▶ IncomingCallActivity
                            ├─ in-app overlay (app visible)
                            ├─ heads-up CallStyle (no full-screen-intent permission)
                            ├─ silent "Reminder: <title>" notification (DND blocks alarms)
                            └─ deferred until a real phone call ends
```

- **Sync** (`SyncCoordinator`): pull sources normalized into `Reminder`s; full snapshots or
  deltas; per-source exponential backoff. Triggers: 15-minute periodic WorkManager jobs (local /
  cloud), app open, a calendar `ContentObserver`, `EVENT_REMINDER`, and a time zone change.
- **Scheduling** (`SchedulingEngine`): plans occurrences for the next 48 h, arms one alarm per
  occurrence (`setAlarmClock` for the soonest), and re-arms on every change, on boot /
  package replace, on time or zone change, daily, and from a 15-minute watchdog that also
  recovers overdue rings (< 2 h late rings, older is marked missed).
- **Ringing**: `AlarmReceiver` takes the DB ring lock (exactly-once across alarm, watchdog and
  the Samsung listener) and starts `RingingService`, which posts the ring notification, plays
  the ringtone (`USAGE_ALARM`) and vibration, times out after 45 s into a ring-back, and pumps
  the queue when the ring ends. All state changes go through `SchedulingEngine.handle`.
- **Time zones**: the injected `Clock` is a `DeviceClock` (zone read on every call). All-day
  events, task due dates, birthdays and habits follow the device zone (`DeviceZonePolicy`; the
  habit behaviour is one switch, `HABITS_FOLLOW_DEVICE_ZONE`): every replan moves them to the
  current zone, so they keep their local wall time after travel.

## Building

Requirements: JDK 17 and the Android SDK (compile/target SDK 35, min SDK 26).

```bash
./gradlew :app:assembleDebug                              # debug APK
./gradlew :core:test :app:testDebugUnitTest                # unit tests (JUnit4, Truth, Robolectric)
./gradlew :app:lintDebug                                   # Android lint
```

Dependency versions are in `gradle/libs.versions.toml`.

## CI

`.github/workflows/ci.yml` runs on every push:

- **`build`**: `:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`.
  Also reports Room schema drift.
- **`instrumented (API 34)` / `instrumented (API 35)`**: boots a `google_apis` x86_64 emulator
  and runs [`scripts/emulator-e2e.sh`](scripts/emulator-e2e.sh): the instrumentation suite
  (`app/src/androidTest/.../e2e`: real alarms, full-screen calls over the lock screen, DND
  modes, time zone changes, BOOT_COMPLETED recovery, calendar provider, onboarding), then a
  shell-driven process-death scenario (arm a ring, kill the process, watch it ring).

A failing job posts a summary (compile errors, failing tests, lint, instrumentation failures,
logcat excerpts) as a commit comment. [`scripts/ci.sh`](scripts/ci.sh) pushes the current
branch, waits for every job and prints PASS or those summaries (`--no-push`, `--only <job>`).

## Running the end-to-end tests locally

With an emulator (API 34 or 35, `google_apis` image so `adb root` works) or a test device
connected:

```bash
bash scripts/emulator-e2e.sh          # full run, as CI does; output in e2e-out/
# or just the instrumentation suite / one class:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=app.call2remind.e2e.ProcessDeathPhase
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.call2remind.e2e.RecoveryE2eTest
```

The tests change device state (Do Not Disturb, time zone, screen and keyguard, a local test
calendar) and restore it afterwards (`E2eRule`). Use a dedicated emulator, not your own phone.
Test notes are logged under the `C2R-E2E` logcat tag.

## Known limitations

- **OAuth is stubbed**: Google Tasks and Microsoft To Do token providers are placeholders and
  the client IDs in `app/build.gradle.kts` are `REPLACE_ME`, so the cloud sources cannot
  connect yet. Their sync, mapping and backoff are implemented and unit-tested against fakes.
- **Samsung is unverified on a device**: the Samsung Reminders notification listener and the
  Samsung battery onboarding (Device Care → Never sleeping apps) are built from documentation
  and tested with synthetic notifications only; no run on real Samsung hardware yet.
- **Do Not Disturb total silence**: Android blocks full-screen intents, alarm sound and alarm
  vibration, so a reminder arrives as a silent notification (Answer / Snooze / Done) rather
  than a call. This is a platform limit, explained to the user by a one-time hint.
- Habits following the device zone after travel is the current default and awaits product
  confirmation (`DeviceZonePolicy.HABITS_FOLLOW_DEVICE_ZONE`).
