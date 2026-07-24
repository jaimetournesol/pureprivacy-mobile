package ai.tournesol.pureprivacy.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.tournesol.pureprivacy.PpSyncService
import ai.tournesol.pureprivacy.R
import java.util.concurrent.TimeUnit

/**
 * Background driver for continuous Backup Sync (feature G). Ensures Tor + the Matrix session are
 * up (so it works even after the app was killed), then runs one [BackupSyncManager.runPass].
 * Runs as a foreground service for its duration so Android doesn't kill a long upload over slow Tor.
 *
 * Scheduled two ways:
 *  - **Periodic** (`pp-backup-periodic`, 15 min) under the user's Wi-Fi/battery constraints — the
 *    catch-up that keeps things flowing even if the app is never opened.
 *  - **One-shot** (`pp-backup-now`) — expedited, fired when a source is enabled, on "Sync now",
 *    or by the MediaStore observer when a new photo appears.
 */
class BackupSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, PpSyncService.CH_STATUS)
            .setContentTitle("PurePrivacy")
            .setContentText("Backing up your files over Tor…")
            .setSmallIcon(R.drawable.ic_sunflower)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    override suspend fun doWork(): Result {
        PpSyncService.ensureChannels(applicationContext)
        runCatching { setForeground(getForegroundInfo()) }   // long uploads survive backgrounding
        if (!BackupSyncManager.ensureSession(applicationContext)) return Result.retry()
        return when (BackupSyncManager.runPass(applicationContext)) {
            is BackupSyncManager.Result.Done -> Result.success()
            BackupSyncManager.Result.AlreadyRunning -> Result.success()
            BackupSyncManager.Result.NotReady -> Result.retry()
        }
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val PERIODIC = "pp-backup-periodic"
        private const val ONESHOT = "pp-backup-now"

        private fun constraints(ctx: Context): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (BackupSyncStore.isWifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(BackupSyncStore.isBatteryNotLow(ctx))
                .build()

        /** Schedule (or cancel) the periodic catch-up based on whether anything is kept in sync
         *  and the current constraints. Call after any source/constraint change. */
        fun applySchedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!BackupSyncStore.hasEnabledSource(ctx)) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val req = PeriodicWorkRequestBuilder<BackupSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints(ctx))
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Kick a prompt one-shot pass (source enabled / "Sync now" / new photo). User-initiated,
         *  so it only requires a connection — it doesn't wait for Wi-Fi. */
        fun syncNow(ctx: Context) {
            if (!BackupSyncStore.hasEnabledSource(ctx)) return
            val req = OneTimeWorkRequestBuilder<BackupSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.KEEP, req)
        }

        fun cancelAll(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(ctx).cancelUniqueWork(ONESHOT)
        }
    }
}
