package ai.tournesol.pureprivacy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import ai.tournesol.pureprivacy.matrix.Notif
import ai.tournesol.pureprivacy.tor.TorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the matrix-rust-sdk sync (and embedded Tor) alive while the app is
 * backgrounded — a foreground service, since PurePrivacy can't use FCM/Google push
 * (that would mean clearnet + a push gateway). A persistent low-priority
 * notification shows the Tor connection; new messages/calls/invites surface as
 * their own notifications, collected from [MatrixRepo.notifications].
 */
class PpSyncService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    // [C4] Guards against two overlapping self-restore attempts (e.g. the OS redelivers
    // onStartCommand while one is already running after a process kill).
    private val reviving = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        // [C4] Reflect the real state right away — after a process-kill restart we are NOT
        // connected yet, so don't flash a misleading "Connected" before onStartCommand.
        startForegroundCompat(if (MatrixRepo.isLoggedIn) "Connected over Tor" else "Reconnecting over Tor…")
        scope.launch { MatrixRepo.notifications.collect { post(it) } }
        // When a call ends (e.g. the caller hung up before answer), clear its ringing
        // notification so the callee stops being rung.
        scope.launch {
            MatrixRepo.callEnded.collect { roomId ->
                runCatching { NotificationManagerCompat.from(this@PpSyncService).cancel(roomId.hashCode()) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // [C4] The service is START_STICKY, so the OS restarts it after a low-memory kill
        // — but the process was torn down, so MatrixRepo.client is null and nothing is
        // delivered, while this notification used to read a misleading "Connected". So:
        // if we're NOT logged in but a saved session exists, re-run the same restore the
        // app's cold start does (wait for Tor Ready -> tryRestore -> startSync), showing
        // "Reconnecting…" until sync is actually Connected. A live session is left alone.
        if (!MatrixRepo.isLoggedIn && MatrixRepo.hasSavedSession(this)) {
            startForegroundCompat("Reconnecting over Tor…")
            reviveSession()
        } else {
            startForegroundCompat(if (MatrixRepo.isLoggedIn) "Connected over Tor" else "Reconnecting over Tor…")
        }
        return START_STICKY
    }

    /** [C4] Re-establish the Matrix session after an OS process kill, mirroring
     *  AppViewModel.startRestore: wait for Tor to be Ready (bounded), tryRestore, then
     *  startSync. Keep the notification on "Reconnecting…" until sync reports Connected,
     *  so the persistent notification never lies about the connection state. Single-flight
     *  via [reviving]. Best-effort: on failure the notification stays "Reconnecting…" and
     *  the next onStartCommand / app open retries. */
    private fun reviveSession() {
        if (!reviving.compareAndSet(false, true)) return
        scope.launch {
            try {
                // Bounded wait for Tor (it starts via PpApp/AppViewModel); don't block forever.
                var waited = 0
                while (TorManager.state.value !is TorManager.State.Ready && waited < 120) {
                    if (MatrixRepo.isLoggedIn) return@launch     // app restored it first
                    delay(1000); waited++
                }
                if (TorManager.state.value !is TorManager.State.Ready) {
                    updateStatus("Reconnecting over Tor…"); return@launch
                }
                if (MatrixRepo.isLoggedIn) return@launch          // app beat us to it
                val ok = runCatching { MatrixRepo.tryRestore(this@PpSyncService) }.getOrDefault(false)
                if (!ok) { updateStatus("Reconnecting over Tor…"); return@launch }
                runCatching { MatrixRepo.startSync() }
                // Reflect the real sync state: only show "Connected" once it actually is.
                var settled = 0
                while (MatrixRepo.status.value != "Connected" && settled < 30) { delay(1000); settled++ }
                updateStatus(if (MatrixRepo.status.value == "Connected") "Connected over Tor" else "Reconnecting over Tor…")
            } finally {
                reviving.set(false)
            }
        }
    }

    private fun updateStatus(text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(1, statusNotification(text))
        }
    }

    private fun statusNotification(text: String) =
        NotificationCompat.Builder(this, CH_STATUS)
            .setContentTitle("PurePrivacy")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_sunflower)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(null, null))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun startForegroundCompat(text: String = "Connected over Tor") {
        val n = statusNotification(text)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    private fun post(n: Notif) {
        if (n.isCall) { postCall(n); return }
        val b = NotificationCompat.Builder(this, CH_MSG)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setSmallIcon(R.drawable.ic_sunflower)
            .setAutoCancel(true)
            .setContentIntent(openApp(n.roomId, n.roomName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching { NotificationManagerCompat.from(this).notify(n.roomId.hashCode(), b.build()) }
    }

    /** Incoming call: a full-screen, ringing notification with Answer/Decline. The
     *  full-screen intent wakes the phone over the lock screen (IncomingCallActivity
     *  owns the ringtone); the actions answer/decline without opening the chat. */
    private fun postCall(n: Notif) {
        val ring = incomingCall(n, null)
        val answer = incomingCall(n, IncomingCallActivity.EXTRA_ANSWER)
        val decline = incomingCall(n, IncomingCallActivity.EXTRA_DECLINE)
        val b = NotificationCompat.Builder(this, CH_CALL)
            .setContentTitle("📞 ${n.roomName}")
            .setContentText("Incoming call · over Tor")
            .setSmallIcon(R.drawable.ic_sunflower)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(ring)
            .setFullScreenIntent(ring, true)
            .addAction(R.drawable.ic_sunflower, "Decline", decline)
            .addAction(R.drawable.ic_sunflower, "Answer", answer)
        runCatching { NotificationManagerCompat.from(this).notify(n.roomId.hashCode(), b.build()) }
    }

    private fun incomingCall(n: Notif, flag: String?): PendingIntent {
        val i = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROOM_ID, n.roomId)
            putExtra(EXTRA_ROOM_NAME, n.roomName)
            if (flag != null) putExtra(flag, true)
            action = "pp.call.${flag ?: "ring"}.${n.roomId}"   // distinct PendingIntent per action
        }
        val pf = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, (n.roomId + (flag ?: "ring")).hashCode(), i, pf)
    }

    private fun openApp(roomId: String?, roomName: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (roomId != null) { putExtra(EXTRA_ROOM_ID, roomId); putExtra(EXTRA_ROOM_NAME, roomName) }
        }
        val pf = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, roomId?.hashCode() ?: 0, i, pf)
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }

    companion object {
        const val CH_STATUS = "pp_status"
        const val CH_MSG = "pp_msg"
        const val CH_CALL = "pp_call"
        const val EXTRA_ROOM_ID = "pp_room_id"
        const val EXTRA_ROOM_NAME = "pp_room_name"
        const val EXTRA_ANSWER = "pp_answer_call"

        fun start(ctx: Context) {
            val i = Intent(ctx, PpSyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** Stop the foreground sync service (and drop its persistent "Connected over Tor"
         *  notification). Used by Pause ("go dark") and by "erase this phone" — the app
         *  is intentionally going offline, so START_STICKY should not resurrect it. */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, PpSyncService::class.java)) }
        }

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Connection", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shows PurePrivacy is connected over Tor" }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_CALL, "Calls", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
