package ai.tournesol.pureprivacy.backup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent config for **continuous** Backup Sync (feature G): which sources are kept in
 * sync (camera roll + picked folders), the per-source incremental watermark, and the
 * Wi-Fi/battery constraints. One-time "Back up files" (feature F) doesn't touch any of this.
 *
 * Stored as JSON in a dedicated SharedPreferences file so it survives reboots and is cheap to
 * read from a background worker. Exposes StateFlows the Backup UI observes live.
 */
object BackupSyncStore {

    enum class Kind { PHOTOS, FOLDER }

    /**
     * One thing we keep in sync.
     * @param id stable identity — "photos" for the camera roll, the tree-URI string for a folder.
     * @param uri persisted tree URI (FOLDER only; null for PHOTOS).
     * @param watermarkMs highest file `date_modified` (ms) fully synced so far.
     * @param boundaryKeys keys of items sitting at exactly [watermarkMs] that already uploaded,
     *   so a `>=` re-scan doesn't double-send them.
     */
    data class Source(
        val id: String,
        val kind: Kind,
        val uri: String?,
        val label: String,
        val enabled: Boolean,
        val watermarkMs: Long,
        val boundaryKeys: Set<String>,
    )

    private const val PREFS = "pp_backup_sync"
    private const val K_SOURCES = "sources"
    private const val K_WIFI_ONLY = "wifi_only"
    private const val K_BATTERY_NOT_LOW = "battery_not_low"
    private const val K_LAST_SYNC = "last_sync_ms"

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources
    private val _wifiOnly = MutableStateFlow(true)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly
    private val _batteryNotLow = MutableStateFlow(true)
    val batteryNotLow: StateFlow<Boolean> = _batteryNotLow
    /** Epoch-ms of the last completed sync pass (0 = never), for the UI's "Last synced …". */
    private val _lastSyncMs = MutableStateFlow(0L)
    val lastSyncMs: StateFlow<Long> = _lastSyncMs

    @Volatile private var loaded = false

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Load once into the flows. Safe to call repeatedly (idempotent). */
    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        _wifiOnly.value = p.getBoolean(K_WIFI_ONLY, true)
        _batteryNotLow.value = p.getBoolean(K_BATTERY_NOT_LOW, true)
        _lastSyncMs.value = p.getLong(K_LAST_SYNC, 0L)
        _sources.value = runCatching { parseSources(p.getString(K_SOURCES, "[]") ?: "[]") }.getOrDefault(emptyList())
        loaded = true
    }

    fun markSynced(ctx: Context, whenMs: Long) {
        ensureLoaded(ctx)
        _lastSyncMs.value = whenMs
        prefs(ctx).edit().putLong(K_LAST_SYNC, whenMs).apply()
    }

    fun snapshot(ctx: Context): List<Source> { ensureLoaded(ctx); return _sources.value }
    fun isWifiOnly(ctx: Context): Boolean { ensureLoaded(ctx); return _wifiOnly.value }
    fun isBatteryNotLow(ctx: Context): Boolean { ensureLoaded(ctx); return _batteryNotLow.value }

    /** True if anything is currently kept in sync (drives whether periodic work should run). */
    fun hasEnabledSource(ctx: Context): Boolean = snapshot(ctx).any { it.enabled }

    @Synchronized
    fun setConstraints(ctx: Context, wifiOnly: Boolean, batteryNotLow: Boolean) {
        ensureLoaded(ctx)
        _wifiOnly.value = wifiOnly; _batteryNotLow.value = batteryNotLow
        prefs(ctx).edit()
            .putBoolean(K_WIFI_ONLY, wifiOnly)
            .putBoolean(K_BATTERY_NOT_LOW, batteryNotLow)
            .apply()
    }

    /** Insert or replace a source (matched by id) and persist. */
    @Synchronized
    fun upsert(ctx: Context, s: Source) {
        ensureLoaded(ctx)
        val next = _sources.value.filter { it.id != s.id } + s
        commit(ctx, next)
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        ensureLoaded(ctx)
        commit(ctx, _sources.value.filter { it.id != id })
    }

    fun get(ctx: Context, id: String): Source? = snapshot(ctx).firstOrNull { it.id == id }

    /** Advance a source's incremental progress after a (partial) sync pass. */
    @Synchronized
    fun updateProgress(ctx: Context, id: String, watermarkMs: Long, boundaryKeys: Set<String>) {
        ensureLoaded(ctx)
        val next = _sources.value.map {
            if (it.id == id) it.copy(watermarkMs = watermarkMs, boundaryKeys = boundaryKeys) else it
        }
        commit(ctx, next)
    }

    private fun commit(ctx: Context, list: List<Source>) {
        _sources.value = list
        prefs(ctx).edit().putString(K_SOURCES, serialize(list)).apply()
    }

    private fun serialize(list: List<Source>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("kind", s.kind.name)
                put("uri", s.uri ?: JSONObject.NULL)
                put("label", s.label)
                put("enabled", s.enabled)
                put("watermarkMs", s.watermarkMs)
                put("boundaryKeys", JSONArray(s.boundaryKeys.toList()))
            })
        }
        return arr.toString()
    }

    private fun parseSources(json: String): List<Source> {
        val arr = JSONArray(json)
        val out = ArrayList<Source>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val keys = HashSet<String>()
            o.optJSONArray("boundaryKeys")?.let { for (j in 0 until it.length()) keys.add(it.getString(j)) }
            out.add(
                Source(
                    id = o.getString("id"),
                    kind = Kind.valueOf(o.getString("kind")),
                    uri = if (o.isNull("uri")) null else o.optString("uri", null),
                    label = o.optString("label", o.getString("id")),
                    enabled = o.optBoolean("enabled", true),
                    watermarkMs = o.optLong("watermarkMs", 0L),
                    boundaryKeys = keys,
                )
            )
        }
        return out
    }
}
