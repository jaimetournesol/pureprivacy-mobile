package ai.tournesol.pureprivacy.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import ai.tournesol.pureprivacy.tor.TorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a **continuous** Backup Sync pass (feature G): for every enabled [BackupSyncStore.Source]
 * (camera roll + picked folders), find files newer than the source's watermark and upload the
 * new ones to the box's library room — reusing feature F's proven [MatrixRepo.backupUpload] path,
 * so there's no new box surface and everything stays E2EE-at-rest, over Tor.
 *
 * Incremental + resumable: enumerate `modified >= watermark`, skip the boundary keys already sent,
 * upload ascending, advance the watermark on each success, stop a source on the first failure so
 * it retries next pass. Single-flight via [running].
 */
object BackupSyncManager {

    private const val TAG = "PpBackupSync"
    private val running = AtomicBoolean(false)

    /** Files still to upload in the pass in flight (0 when idle) — drives the UI's "Syncing N…". */
    val syncingCount = MutableStateFlow(0)
    val isRunning: StateFlow<Boolean> get() = _isRunning
    private val _isRunning = MutableStateFlow(false)

    private const val PERSIST_EVERY = 8   // flush watermark every N uploads so a kill loses little

    private data class Item(
        val key: String, val uri: Uri, val modifiedMs: Long, val sizeBytes: Long,
    )

    /** Outcome of a pass, for the worker's retry decision. */
    sealed class Result {
        object AlreadyRunning : Result()
        object NotReady : Result()
        data class Done(val uploaded: Int, val failed: Int, val skipped: Int) : Result()
    }

    /**
     * Ensure Tor is up and the Matrix session is restored, so a cold background worker can upload.
     * Mirrors PpSyncService.reviveSession. Returns true once logged in. Bounded so it never hangs.
     */
    suspend fun ensureSession(ctx: Context): Boolean {
        if (MatrixRepo.isLoggedIn) return true
        if (!MatrixRepo.hasSavedSession(ctx)) return false
        runCatching { TorManager.start(ctx) }
        var waited = 0
        while (TorManager.state.value !is TorManager.State.Ready && waited < 120) {
            if (MatrixRepo.isLoggedIn) return true
            delay(1000); waited++
        }
        if (TorManager.state.value !is TorManager.State.Ready) return MatrixRepo.isLoggedIn
        if (!MatrixRepo.isLoggedIn) {
            if (!runCatching { MatrixRepo.tryRestore(ctx) }.getOrDefault(false)) return false
            runCatching { MatrixRepo.startSync() }
        }
        return MatrixRepo.isLoggedIn
    }

    /** One sync pass over every enabled source. Assumes [ensureSession] already succeeded (the
     *  warm path — an open app / live service — satisfies that for free). */
    suspend fun runPass(ctx: Context): Result {
        if (!running.compareAndSet(false, true)) return Result.AlreadyRunning
        _isRunning.value = true
        try {
            BackupSyncStore.ensureLoaded(ctx)
            if (!MatrixRepo.isLoggedIn) return Result.NotReady
            val room = MatrixRepo.ensureBackupRoom() ?: return Result.NotReady
            var uploaded = 0; var failed = 0; var skipped = 0

            for (src in BackupSyncStore.snapshot(ctx).filter { it.enabled }) {
                val items = enumerate(ctx, src)
                    .filter { it.key !in src.boundaryKeys }
                    .sortedBy { it.modifiedMs }
                if (items.isEmpty()) continue
                syncingCount.value += items.size

                var wm = src.watermarkMs
                var boundary = HashSet(src.boundaryKeys)
                var sinceFlush = 0
                var stopped = false

                for (item in items) {
                    if (item.sizeBytes > MatrixRepo.MAX_BACKUP_BYTES) {
                        // Can't ship it — record we've moved past it so it isn't re-scanned forever.
                        Log.w(TAG, "skip oversized ${item.uri} (${item.sizeBytes} B)")
                        skipped++
                        wm = advance(wm, boundary, item)
                        sinceFlush++
                        syncingCount.value = (syncingCount.value - 1).coerceAtLeast(0)
                        continue
                    }
                    val ok = runCatching { MatrixRepo.backupUpload(ctx, room, item.uri) }.getOrDefault(false)
                    syncingCount.value = (syncingCount.value - 1).coerceAtLeast(0)
                    if (ok) {
                        uploaded++
                        wm = advance(wm, boundary, item)
                        if (++sinceFlush >= PERSIST_EVERY) {
                            BackupSyncStore.updateProgress(ctx, src.id, wm, boundary); sinceFlush = 0
                        }
                    } else {
                        // Network/Tor hiccup — leave the watermark; retry this item next pass.
                        failed++; stopped = true; break
                    }
                }
                // Persist whatever progress this source made (also clears the in-flight remainder).
                BackupSyncStore.updateProgress(ctx, src.id, wm, boundary)
                if (stopped) { syncingCount.value = 0; break }
            }
            BackupSyncStore.markSynced(ctx, System.currentTimeMillis())
            Log.i(TAG, "pass done: up=$uploaded fail=$failed skip=$skipped")
            return Result.Done(uploaded, failed, skipped)
        } finally {
            syncingCount.value = 0
            _isRunning.value = false
            running.set(false)
        }
    }

    /** Advance the watermark past [item], updating the boundary key-set at the new max second. */
    private fun advance(wm: Long, boundary: HashSet<String>, item: Item): Long {
        return when {
            item.modifiedMs > wm -> { boundary.clear(); boundary.add(item.key); item.modifiedMs }
            item.modifiedMs == wm -> { boundary.add(item.key); wm }
            else -> wm   // shouldn't happen (ascending), keep as-is
        }
    }

    private fun enumerate(ctx: Context, src: BackupSyncStore.Source): List<Item> = when (src.kind) {
        BackupSyncStore.Kind.PHOTOS -> enumeratePhotos(ctx, src.watermarkMs)
        BackupSyncStore.Kind.FOLDER -> src.uri?.let { enumerateFolder(ctx, Uri.parse(it), src.watermarkMs) } ?: emptyList()
    }

    private fun enumeratePhotos(ctx: Context, watermarkMs: Long): List<Item> {
        val out = ArrayList<Item>()
        val sinceSec = (watermarkMs / 1000).coerceAtLeast(0)
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE)
        val sel = "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
        val args = arrayOf(sinceSec.toString())
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} ASC"
        for ((collection, prefix) in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "img",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "vid",
        )) {
            runCatching {
                ctx.contentResolver.query(collection, proj, sel, args, sort)?.use { c ->
                    val idc = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val mc = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val sc = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        val id = c.getLong(idc)
                        out.add(
                            Item(
                                key = "$prefix:$id",
                                uri = ContentUris.withAppendedId(collection, id),
                                modifiedMs = c.getLong(mc) * 1000L,
                                sizeBytes = c.getLong(sc),
                            )
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "photo query failed: ${it.message}") }
        }
        return out
    }

    private fun enumerateFolder(ctx: Context, tree: Uri, watermarkMs: Long): List<Item> {
        val root = runCatching { DocumentFile.fromTreeUri(ctx, tree) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Item>()
        val stack = ArrayDeque<DocumentFile>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 20000) {
            guard++
            val dir = stack.removeLast()
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in kids) {
                if (f.isDirectory) { stack.addLast(f); continue }
                if (!f.isFile) continue
                val mod = f.lastModified()
                if (mod < watermarkMs) continue
                out.add(Item(key = f.uri.toString(), uri = f.uri, modifiedMs = mod, sizeBytes = f.length()))
            }
        }
        return out
    }
}
