# Call2Remind — working notes for agents

Read `docs/SPEC.md` first.

## Rules
- Work on branch `feat/v1-engine`. Never push to `main`. Never force-push.
- Commits: short imperative subject, optional body. **No `Co-Authored-By` or other trailers.**
- This sandbox cannot download Gradle/Android deps, so you **cannot build locally**. Build and test via CI:
  `scripts/ci.sh` pushes the current branch and waits for GitHub Actions, printing PASS or the failure summary (compile errors, failing tests, lint). Each run takes ~4–10 min, so batch changes and read code carefully before pushing.
- Only one agent pushes at a time. Commit only your own files; `git pull --rebase` before pushing if the remote moved.

## Layout
- `:core` — pure Kotlin (JVM 17). Domain models and all logic that doesn't need Android: recurrence, occurrence planning, state machine, snooze policy, default times, dedupe, request codes. Must be heavily unit-tested (JUnit4 + Truth). Use `java.time`; inject `Clock`.
- `:app` — Android (`app.call2remind`). Hilt, Room (KSP), WorkManager, DataStore, Compose. Robolectric + MockK + Turbine for tests. Put Android-facing code behind interfaces so logic stays testable.
- Package root: `app.call2remind` (core: `app.call2remind.core`).

## Conventions
- Kotlin official style, explicit visibility where helpful, no `!!` in production code.
- Coroutines + Flow; no blocking on main. Dispatchers injected.
- Every public behaviour gets a test. Prefer fakes over mocks for our own interfaces.
- Versions live in `gradle/libs.versions.toml`; add deps there.
