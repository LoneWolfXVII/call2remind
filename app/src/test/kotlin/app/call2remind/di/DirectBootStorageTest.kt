package app.call2remind.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.call2remind.data.db.Call2RemindDb
import app.call2remind.data.repo.RoomSourceRepository
import app.call2remind.settings.DataStoreSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The ring path must work before first unlock, so its storage lives in device-protected storage. */
@RunWith(RobolectricTestRunner::class)
class DirectBootStorageTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val deviceProtected = app.createDeviceProtectedStorageContext()

    @Test
    fun databaseIsCreatedInDeviceProtectedStorage() = runBlocking<Unit> {
        val db = Call2RemindDb.create(app)
        try {
            RoomSourceRepository(db).getAll()
            assertThat(deviceProtected.getDatabasePath(Call2RemindDb.NAME).exists()).isTrue()
        } finally {
            db.close()
        }
    }

    @Test
    fun settingsDataStoreIsCreatedInDeviceProtectedStorage() = runBlocking<Unit> {
        val store = DataStoreModule.settingsDataStore(app, Dispatchers.IO)
        DataStoreSettingsRepository(store).update { it.copy(ttsEnabled = false) }

        val file = deviceProtected.filesDir.resolve("datastore/settings.preferences_pb")
        assertThat(file.exists()).isTrue()
        if (deviceProtected.filesDir != app.filesDir) {
            assertThat(app.filesDir.resolve("datastore/settings.preferences_pb").exists()).isFalse()
        }
    }
}
