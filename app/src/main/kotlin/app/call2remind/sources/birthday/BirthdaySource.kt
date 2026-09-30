package app.call2remind.sources.birthday

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.call2remind.core.model.SourceType
import app.call2remind.di.IoDispatcher
import app.call2remind.sources.NeedsPermissionException
import app.call2remind.sources.ReminderSource
import app.call2remind.sources.SourceAvailability
import app.call2remind.sources.SourceSnapshot
import app.call2remind.sources.SyncRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Read access to contact birthdays. Real implementation: [ContentResolverContactsReader]. */
interface ContactsReader {
    /** True if `READ_CONTACTS` is granted. */
    fun hasPermission(): Boolean

    fun birthdays(): List<ContactBirthdayRow>
}

/** Contact birthdays → annual reminders (full snapshot every sync). Mapping: [BirthdayMapper]. */
@Singleton
class BirthdaySource @Inject constructor(
    private val reader: ContactsReader,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ReminderSource {
    override val type: SourceType = SourceType.BIRTHDAY
    override val requiresNetwork: Boolean = false

    override suspend fun availability(): SourceAvailability =
        if (reader.hasPermission()) SourceAvailability.Ready else SourceAvailability.NeedsPermission(READ_CONTACTS)

    override suspend fun snapshot(request: SyncRequest): SourceSnapshot = withContext(io) {
        if (!reader.hasPermission()) throw NeedsPermissionException(READ_CONTACTS)
        val rows = try {
            reader.birthdays()
        } catch (e: SecurityException) {
            throw NeedsPermissionException(READ_CONTACTS)
        }
        SourceSnapshot.Full(BirthdayMapper.map(rows, request.settings.birthdayDayBefore, request.zone))
    }

    companion object {
        const val READ_CONTACTS: String = "android.permission.READ_CONTACTS"
    }
}

/** [ContactsReader] over `ContactsContract.Data`. Blocking: call off the main thread. */
@Singleton
class ContentResolverContactsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactsReader {
    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun birthdays(): List<ContactBirthdayRow> {
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Event.START_DATE,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
        val args = arrayOf(
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString(),
        )
        return context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ContactBirthdayRow(
                            contactId = c.getLong(0),
                            displayName = if (c.isNull(1)) null else c.getString(1),
                            startDate = if (c.isNull(2)) null else c.getString(2),
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}
