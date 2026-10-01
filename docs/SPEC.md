# Call2Remind — Engineering Spec (v1)

Android app that delivers reminders as unignorable **full-screen incoming "calls"** instead of dismissable notifications. Differentiator: **auto-sync** from the phone's existing reminder sources — zero re-entry.

Source of truth for product decisions: the "Call Reminder App — Spec" doc (claude.ai). This file is the engineering copy.

## Decisions
- Android-first. `targetSdk 35` (Android 15), Android 14 is a first-class tested target, `minSdk 26` (degraded mode on old phones).
- Samsung Reminders: ship the **NotificationListener fallback in v1** (opt-in, "best effort"); Microsoft To Do / Graph path later in the same v1 build plan (phase 3).
- Date-only items (Google Tasks, birthdays, all-day calendar events, Samsung Reminders) fire at a **default time configured per source**, editable in Settings.
- No server. Everything on-device.

## Reminder sources
| Source | Read via | Notes |
|---|---|---|
| Calendar events (any synced account) | `CalendarContract.Instances` (next N days), `CalendarContract.Reminders` for per-event offsets, `ContentObserver`, `EVENT_REMINDER` broadcast backup | All-day → per-source default time. Recurring expanded by Instances. Fire at the event's own reminder offset if present, else at start. |
| Google Tasks | Tasks REST API v1, `tasks.readonly`, OAuth via Credential Manager / AuthorizationClient; poll every 15 min + on app open | `due` is **date-only** → default time. Incremental via `updatedMin`. |
| Samsung Reminders | (b) `NotificationListenerService` on `com.samsung.android.app.reminder` → replace its notification with our call (reactive, no pre-scheduling). (a) Microsoft To Do via Graph `/me/todo/lists/{id}/tasks` (`reminderDateTime`), delta queries | Best effort. Client IDs are placeholders (`BuildConfig`). |
| Birthdays | `ContactsContract.Data` with `Event.TYPE_BIRTHDAY`; Google Birthdays calendar | Parse `yyyy-MM-dd` and `--MM-dd`. Dedupe name+date. Default time per source. Optional day-before call. |
| Habits | In-app, Room; recurrence = days of week + times (+ interval) | Parity with manual-entry competitors. |
| Alarms | Deferred | |

## Call experience
- `IncomingCallActivity` (`setShowWhenLocked`, `setTurnScreenOn`, keep screen on) launched from `NotificationCompat.CallStyle.forIncomingCall()` notification with `setFullScreenIntent`, `IMPORTANCE_HIGH` channel, ongoing.
- Ringtone: looping `MediaPlayer` on `USAGE_ALARM` + vibration; per-source override. Ring **45 s** then auto-snooze + "missed reminder" notification.
- Accept → stop ring, TTS "Reminder: <title>. <time or notes>", then Done / Snooze / Open in source app.
- Decline = snooze 5 min (10 / 15 / custom). **Max 3 ring-backs**, then missed.

### Edge cases
| Situation | Behaviour |
|---|---|
| Phone locked | Full-screen call over the keyguard (`setShowWhenLocked`, `setTurnScreenOn`). |
| DND priority / alarms-only (alarms allowed) | Rings normally: the ringing notification is `CATEGORY_ALARM` on a `USAGE_ALARM` channel, the ringtone is `USAGE_ALARM`. |
| DND total silence, or a priority policy that excludes alarms | Android suppresses the full-screen intent and drops alarm sound **and** vibration, so there is no "call". The ring is a silent, high-priority, non-insistent **"Reminder: \<title\>"** notification (no full-screen intent) with **Answer / Snooze / Done** actions; it waits in the shade and on the lock screen until the user wakes the phone. No ringtone, no vibration (the platform would ignore it). The occurrence is RINGING as usual and times out into a ring-back / missed like any ring. One-time hint notification explains this. |
| Already in a phone call (`AudioManager.mode`) | Silent heads-up now; rings when the call ends. |
| App in foreground, screen on | In-app overlay instead of the full-screen activity. |
| No full-screen intent permission (Android 14+) | Heads-up CallStyle notification + ringtone from the foreground service. |
| Missed while off (boot) | Overdue < 2 h rings; older → missed. |
| Two due at once | Queue: earliest first; ties ring meetings, then tasks, habits, birthdays. |
| Time zone change (travel) | Device-zone reminders keep their **local wall time**: all-day events, Google Tasks due dates, birthdays and (switch `DeviceZonePolicy.HABITS_FOLLOW_DEVICE_ZONE`, default on) habits are moved to the new zone by the replan that runs in the TIMEZONE_CHANGED receiver; a forced re-sync (WorkManager `REPLACE`) follows. MS To Do keeps its explicit zone; timed events are instants. SNOOZED / RINGING occurrences are kept as they are. |
| Sub-minute snooze length | Labels show seconds ("Snooze 30 s"); partial minutes round up. Never "0 min". |

## Architecture
- Modules: `:core` (pure Kotlin, all logic that doesn't need Android — fully unit-tested) and `:app` (Android).
- Scheduling: one alarm per occurrence in a **48 h window**; rest in Room; daily top-up. `setAlarmClock()` for the next occurrence, `setExactAndAllowWhileIdle()` for others. Re-arm on every fire. `USE_EXACT_ALARM` primary, `SCHEDULE_EXACT_ALARM` for API 31–32.
- Flow: alarm → `AlarmReceiver` → `RingingService` (FGS `specialUse`, subtype "reminder ringing") → CallStyle notification + FSI → `IncomingCallActivity`.
- FSI risk (Android 14+): check `canUseFullScreenIntent()`; deep-link `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`; degraded mode = heads-up CallStyle + ringtone from FGS. No `SYSTEM_ALERT_WINDOW`.
- Sync: `SyncCoordinator` normalizes sources into `Reminder` + `Occurrence`. Dedupe key = source + external id + occurrence start. WorkManager periodic 15 min.
- Re-arm on `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_CHANGED`, `TIMEZONE_CHANGED`. Direct-boot-aware storage for the 48 h window.
- Room: `sources(id, type, account, enabled, lastSyncAt)`, `reminders(id, sourceId, externalId, title, notes, rrule, defaultTime, ringtoneUri, ttsEnabled)`, `occurrences(id, reminderId, fireAt, state, snoozeCount, alarmRequestCode)`; state ∈ scheduled, ringing, snoozed, done, missed. Settings in DataStore.
- Permissions: READ_CALENDAR, READ_CONTACTS, POST_NOTIFICATIONS, USE_FULL_SCREEN_INTENT, USE_EXACT_ALARM, SCHEDULE_EXACT_ALARM (31–32), RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, VIBRATE, WAKE_LOCK, INTERNET, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, BIND_NOTIFICATION_LISTENER_SERVICE (opt-in). No READ_PHONE_STATE.
- Samsung battery onboarding: deep links to battery optimization exemption, Device Care → Never sleeping apps. Self-test "Ring me in 1 minute".

## Backend robustness (never miss a ring)
- Room is the single source of truth; state transitions in one transaction.
- Idempotent scheduling: request code = stable hash of occurrence id.
- Reconciliation on app start, boot, time change, each sync.
- Watchdog WorkManager job every 15 min: overdue "scheduled" → ring (or missed if > 2 h late).
- Exactly-once ring: DB ringing lock shared by alarm, watchdog, Samsung listener.
- FGS start failure (`ForegroundServiceStartNotAllowedException`) → high-priority CallStyle notification with sound.
- Sync: incremental, exponential backoff + jitter, token refresh, offline-first.
- Time: instants in UTC + source zone; tests for DST, zone travel, month-end recurrences. The app clock reads the device zone at every call (`DeviceClock`), never a zone frozen at process start; `DeviceZonePolicy` decides which reminders follow the device zone.
- Tokens in Keystore-backed storage. No analytics.
- Local ring log (fired/answered/snoozed/missed + reason) for a debug screen.

## Motion (frontend)
Springs everywhere (default `dampingRatio 0.7, StiffnessMediumLow`; 0.5 playful; 1.0 precise), interruptible, haptics paired (SEGMENT_TICK, CONFIRM, REJECT), respect "Remove animations". Key moments: lamp pulse; plug drag with rubber-band + ticks + snap; answer = panel expands (shared element); TTS word highlight via `onRangeStart`; snooze sheet; staggered timeline; collapsing "next call" strip (scroll-linked); Home→Detail shared element; swipe row done/skip; predictive back. Perf: baseline profiles, stable state, jank < 1%.

## Design
"Exchange Lamp" (telephone-exchange inspired). Schibsted Grotesk UI, JetBrains Mono for times (tabular). Ground #ECEEE8, surface #F7F8F4, ink #1B2420, muted #4F5A54, line #C9CFC5, panel bottle-green #1F3B31, lamp amber #F2A900 (fill behind dark text only), missed red #B3321E. Screens: Welcome, Permissions, Samsung battery, Self-test, Incoming call (plug-into-socket answer), Answered (TTS), Snooze sheet, Heads-up, Home/Up next, Sources, New habit, Missed/History, Settings, Ringtone picker, Reminder detail, Empty home.
