package app.call2remind.sources.auth

import app.call2remind.BuildConfig
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** OAuth access tokens for a cloud source. Implementations must be safe to call concurrently. */
interface TokenProvider {
    /** True if the user connected an account (a token can be obtained, possibly after refresh). */
    suspend fun isConnected(): Boolean

    /** A valid access token, refreshing if needed; `null` when not connected. */
    suspend fun accessToken(): String?

    /** Marks [token] as rejected by the server (HTTP 401) so the next [accessToken] refreshes. */
    suspend fun invalidate(token: String)

    /** Forgets the account (Sources screen → Disconnect). */
    suspend fun disconnect()
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleTasksAuth

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MicrosoftAuth

/**
 * Placeholder for Google Tasks OAuth (`tasks.readonly`). The real implementation will use
 * Credential Manager + `AuthorizationClient` (Play Services) with [clientId]; until then it always
 * reports "not connected", so the Google Tasks source shows NotConnected and never calls the API.
 */
@Singleton
class GoogleAuthTokenProvider @Inject constructor() : TokenProvider {
    val clientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    override suspend fun isConnected(): Boolean = false

    override suspend fun accessToken(): String? = null

    override suspend fun invalidate(token: String) = Unit

    override suspend fun disconnect() = Unit

    companion object {
        const val SCOPE_TASKS_READONLY: String = "https://www.googleapis.com/auth/tasks.readonly"
    }
}

/**
 * Placeholder for Microsoft Graph OAuth (`Tasks.Read`, `offline_access`). The real implementation
 * will use MSAL with [clientId]; until then it always reports "not connected".
 */
@Singleton
class MicrosoftAuthTokenProvider @Inject constructor() : TokenProvider {
    val clientId: String = BuildConfig.MS_CLIENT_ID

    override suspend fun isConnected(): Boolean = false

    override suspend fun accessToken(): String? = null

    override suspend fun invalidate(token: String) = Unit

    override suspend fun disconnect() = Unit

    companion object {
        val SCOPES: List<String> = listOf("Tasks.Read", "offline_access")
    }
}
