package app.call2remind.di

import app.call2remind.sources.ReminderSource
import app.call2remind.sources.auth.GoogleAuthTokenProvider
import app.call2remind.sources.auth.GoogleTasksAuth
import app.call2remind.sources.auth.MicrosoftAuth
import app.call2remind.sources.auth.MicrosoftAuthTokenProvider
import app.call2remind.sources.auth.TokenProvider
import app.call2remind.sources.birthday.BirthdaySource
import app.call2remind.sources.birthday.ContactsReader
import app.call2remind.sources.birthday.ContentResolverContactsReader
import app.call2remind.sources.calendar.CalendarReader
import app.call2remind.sources.calendar.CalendarSource
import app.call2remind.sources.calendar.ContentResolverCalendarReader
import app.call2remind.sources.google.GoogleTasksApi
import app.call2remind.sources.google.GoogleTasksSource
import app.call2remind.sources.habit.HabitRepository
import app.call2remind.sources.habit.ReminderBackedHabitRepository
import app.call2remind.sources.http.AuthorizedHttp
import app.call2remind.sources.mstodo.GraphTodoApi
import app.call2remind.sources.mstodo.MsTodoSource
import app.call2remind.sources.samsung.AndroidNotificationAccess
import app.call2remind.sources.samsung.NotificationAccess
import app.call2remind.sync.SyncScheduler
import app.call2remind.sync.WorkManagerSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Reminder sources, their readers / API clients, and sync scheduling. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SourcesModule {
    @Binds
    abstract fun calendarReader(impl: ContentResolverCalendarReader): CalendarReader

    @Binds
    abstract fun contactsReader(impl: ContentResolverContactsReader): ContactsReader

    @Binds
    @IntoSet
    abstract fun calendarSource(impl: CalendarSource): ReminderSource

    @Binds
    @IntoSet
    abstract fun birthdaySource(impl: BirthdaySource): ReminderSource

    @Binds
    abstract fun habits(impl: ReminderBackedHabitRepository): HabitRepository

    @Binds
    @GoogleTasksAuth
    abstract fun googleTokens(impl: GoogleAuthTokenProvider): TokenProvider

    @Binds
    @MicrosoftAuth
    abstract fun microsoftTokens(impl: MicrosoftAuthTokenProvider): TokenProvider

    @Binds
    abstract fun notificationAccess(impl: AndroidNotificationAccess): NotificationAccess

    @Binds
    abstract fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    companion object {
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 30L

        @Provides
        @Singleton
        fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        @Provides
        @Singleton
        fun googleTasksApi(
            client: OkHttpClient,
            @GoogleTasksAuth tokens: TokenProvider,
            @IoDispatcher io: CoroutineDispatcher,
        ): GoogleTasksApi = GoogleTasksApi(AuthorizedHttp(client, tokens, io = io))

        @Provides
        @Singleton
        fun graphTodoApi(
            client: OkHttpClient,
            @MicrosoftAuth tokens: TokenProvider,
            @IoDispatcher io: CoroutineDispatcher,
        ): GraphTodoApi = GraphTodoApi(AuthorizedHttp(client, tokens, io = io))

        @Provides
        @IntoSet
        fun googleTasksSource(api: GoogleTasksApi, @GoogleTasksAuth tokens: TokenProvider): ReminderSource =
            GoogleTasksSource(api, tokens)

        @Provides
        @IntoSet
        fun msTodoSource(api: GraphTodoApi, @MicrosoftAuth tokens: TokenProvider): ReminderSource =
            MsTodoSource(api, tokens)
    }
}
