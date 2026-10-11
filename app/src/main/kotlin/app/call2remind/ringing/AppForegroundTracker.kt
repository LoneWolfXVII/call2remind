package app.call2remind.ringing

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks whether any of our activities is started (visible). Register once from the Application. */
@Singleton
class AppForegroundTracker @Inject constructor() : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private val _inForeground = MutableStateFlow(false)

    /** Emits true while at least one activity is started. */
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    val isInForeground: Boolean get() = _inForeground.value

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        _inForeground.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        _inForeground.value = started > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
