package app.call2remind.sources.birthday

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import app.call2remind.settings.SourceSettings
import app.call2remind.sources.NeedsPermissionException
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.SourceSnapshot
import app.call2remind.sources.SyncRequest
import app.call2remind.testing.T0
import app.call2remind.testing.UTC
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

class FakeContactsReader(
    var permission: Boolean = true,
    var rows: List<ContactBirthdayRow> = emptyList(),
    var failWith: RuntimeException? = null,
) : ContactsReader {
    override fun hasPermission(): Boolean = permission

    override fun birthdays(): List<ContactBirthdayRow> {
        failWith?.let { throw it }
        return rows
    }
}

/** Contacts provider serving Data rows, recording the selection. */
class FakeContactsProvider : ContentProvider() {
    var lastSelection: String? = null
    var lastArgs: List<String>? = null
    var rows: List<Array<Any?>> = emptyList()

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        lastSelection = selection
        lastArgs = selectionArgs?.toList()
        return MatrixCursor(projection ?: emptyArray()).apply { rows.forEach { addRow(it) } }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

@RunWith(RobolectricTestRunner::class)
class BirthdaySourceTest {
    private val reader = FakeContactsReader()
    private val source = BirthdaySource(reader, Dispatchers.Unconfined)
    private val request = SyncRequest(T0, UTC, null, SourceSettings())

    @Test
    fun availabilityFollowsThePermission() = runBlocking<Unit> {
        assertThat(source.requiresNetwork).isFalse()
        assertThat(source.availability()).isEqualTo(SourceAvailability.Ready)
        reader.permission = false
        assertThat(source.availability()).isEqualTo(SourceAvailability.NeedsPermission(Manifest.permission.READ_CONTACTS))
    }

    @Test
    fun snapshotIsFullAndHonoursDayBefore() = runBlocking<Unit> {
        reader.rows = listOf(ContactBirthdayRow(1, "Asha", "1990-05-17"), ContactBirthdayRow(2, "Asha", "--05-17"))

        val plain = source.snapshot(request) as SourceSnapshot.Full
        val withDayBefore = source.snapshot(request.copy(settings = SourceSettings(birthdayDayBefore = true))) as SourceSnapshot.Full

        assertThat(plain.reminders.map { it.externalId }).containsExactly("asha|05-17")
        assertThat(withDayBefore.reminders.map { it.externalId }).containsExactly("asha|05-17", "asha|05-17|day-before")
        assertThat(plain.cursor).isNull()
    }

    @Test
    fun missingOrRevokedPermissionThrowsNeedsPermission() {
        reader.permission = false
        assertThrows(NeedsPermissionException::class.java) { runBlocking { source.snapshot(request) } }
        reader.permission = true
        reader.failWith = SecurityException("revoked")
        assertThrows(NeedsPermissionException::class.java) { runBlocking { source.snapshot(request) } }
    }

    @Test
    fun contentResolverReaderQueriesBirthdayEvents() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val provider = Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        provider.rows = listOf(arrayOf(7L, "Asha", "1990-05-17"), arrayOf(8L, null, "--01-02"))
        val contacts = ContentResolverContactsReader(app)

        assertThat(contacts.hasPermission()).isFalse()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertThat(contacts.hasPermission()).isTrue()

        assertThat(contacts.birthdays()).containsExactly(
            ContactBirthdayRow(7, "Asha", "1990-05-17"),
            ContactBirthdayRow(8, null, "--01-02"),
        ).inOrder()
        assertThat(provider.lastArgs).containsExactly(
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString(),
        ).inOrder()
        assertThat(provider.lastSelection).contains(ContactsContract.Data.MIMETYPE)
    }
}
