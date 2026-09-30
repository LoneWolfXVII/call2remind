package app.call2remind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import app.call2remind.sync.CalendarChangeObserver
import app.call2remind.sync.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var calendarObserver: CalendarChangeObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Text("Call2Remind") }
    }

    override fun onStart() {
        super.onStart()
        // App open: expedited one-shot sync of every source; permissions may have been granted meanwhile.
        calendarObserver.ensureRegistered()
        syncScheduler.requestSync()
    }
}
