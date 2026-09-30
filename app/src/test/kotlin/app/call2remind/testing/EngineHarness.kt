package app.call2remind.testing

import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.repo.RoomOccurrenceRepository
import app.call2remind.data.repo.RoomReminderRepository
import app.call2remind.data.repo.RoomSourceRepository
import app.call2remind.scheduling.SchedulingEngine
import app.call2remind.settings.Settings

/**
 * Real Room repositories on an in-memory database plus fakes for everything Android-facing,
 * wired into a real [SchedulingEngine]. Requires Robolectric. Call [close] after the test.
 */
class EngineHarness(
    val clock: MutableClock = MutableClock(),
    initialSettings: Settings = Settings(),
) {
    val db: Call2RemindDb = newTestDb()
    val settings = FakeSettingsRepository(initialSettings)
    val reminders = RoomReminderRepository(db, clock)
    val occurrences = RoomOccurrenceRepository(db, settings, clock)
    val sources = RoomSourceRepository(db)
    val alarms = FakeAlarmScheduler()
    val ringContext = FakeRingContextProvider()
    val missed = FakeMissedNotifier()
    val engine = SchedulingEngine(reminders, occurrences, alarms, settings, ringContext, missed, clock)

    fun close() = db.close()
}
