package ai.tournesol.pureprivacy

import android.app.Application
import android.util.Log
import ai.tournesol.pureprivacy.matrix.MatrixRepo

class PpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // [C4] Bring the sync service up on process start when there's a session to
        // restore — so it survives process death (a low-memory kill, or a cold restart to
        // deliver a notification/broadcast), not only the START_STICKY self-restart. The
        // service's onStartCommand re-runs the Tor-wait + tryRestore + startSync, so a
        // killed session is re-established without the user opening the app. Guarded: only
        // when a session exists (never pre-login), and wrapped — a background-start that
        // Android 12+ refuses must not crash the app; the next app open will start it.
        if (runCatching { MatrixRepo.hasSavedSession(this) }.getOrDefault(false)) {
            runCatching { PpSyncService.start(this) }
                .onFailure { Log.w("PpApp", "could not start sync service from onCreate: ${it.message}") }
        }
    }
}
