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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the matrix-rust-sdk sync (and embedded Tor) alive while the app is
 * backgrounded — a foreground service, since PurePrivacy can't use FCM/Google push
 * (that would mean clearnet + a push gateway). A persistent low-priority
 * notification shows the Tor connection; new messages/calls/invites surface as
 * their own notifications, collected from [MatrixRepo.notifications].
 */
class PpSyncService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        startForegroundCompat()
        scope.launch { MatrixRepo.notifications.collect { post(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val n = NotificationCompat.Builder(this, CH_STATUS)
            .setContentTitle("PurePrivacy")
            .setContentText("Connected over Tor")
            .setSmallIcon(R.drawable.ic_sunflower)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(null, null))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    private fun post(n: Notif) {
        val b = NotificationCompat.Builder(this, if (n.isCall) CH_CALL else CH_MSG)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setSmallIcon(R.drawable.ic_sunflower)
            .setAutoCancel(true)
            .setContentIntent(openApp(n.roomId, n.roomName))
            .setPriority(if (n.isCall) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (n.isCall) b.setCategory(NotificationCompat.CATEGORY_CALL)
        runCatching { NotificationManagerCompat.from(this).notify(n.roomId.hashCode(), b.build()) }
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

        fun start(ctx: Context) {
            val i = Intent(ctx, PpSyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
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
