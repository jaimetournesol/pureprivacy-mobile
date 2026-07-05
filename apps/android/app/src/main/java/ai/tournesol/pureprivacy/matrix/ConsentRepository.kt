package ai.tournesol.pureprivacy.matrix

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import java.util.Collections

/**
 * Single source of truth for which contacts the user has consented to (scanned).
 *
 * Mirrors the `ai.tournesol.pureprivacy.pairings` account-data object
 * `{"onions":[...]}` on the user's own box — the EXACT shape the desktop box
 * reconciles into its federation allowlist (see fedauth.rs / run_pairing_sync), so
 * the JSON format written here must never drift. Account-data is authoritative: we
 * REPLACE the in-memory set from it (never union), so a removal on another device
 * propagates and a removed peer can't be resurrected. Reads/writes go to the box
 * over Tor, which is flaky, hence the retry on writes.
 *
 * Extracted from MatrixRepo (review item W3-T3) so the consent state + its
 * account-data ops live in one cohesive place instead of being threaded through the
 * whole repo. MatrixRepo passes the live [Client] in (it can change on re-login).
 */
class ConsentRepository {

    companion object {
        const val ACCOUNT_DATA_TYPE = "ai.tournesol.pureprivacy.pairings"
        private const val TAG = "PpConsent"
        private const val WRITE_RETRIES = 8
        private const val RETRY_DELAY_MS = 3000L
    }

    /** Onions of contacts we've scanned (mirrors [ACCOUNT_DATA_TYPE]). */
    private val onions = Collections.synchronizedSet(mutableSetOf<String>())

    /** Wall-clock ms of the last successful [load], for the caller's read debounce. */
    @Volatile
    var lastReadMs: Long = 0L
        private set

    /** True iff [onion] is non-null and currently consented. */
    operator fun contains(onion: String?): Boolean =
        onion != null && synchronized(onions) { onion in onions }

    /** Load consent from authoritative account-data, REPLACING the in-memory set
     *  (clear + fill). Best-effort: a failed/empty read keeps the existing set, so a
     *  transient Tor read never drops consent. Re-run on every RUNNING sync cycle. */
    suspend fun load(c: Client) {
        val raw = runCatching { c.accountData(ACCOUNT_DATA_TYPE) }.getOrNull() ?: return
        runCatching {
            val arr = JSONObject(raw).optJSONArray("onions") ?: return
            val fresh = mutableSetOf<String>()
            for (i in 0 until arr.length()) fresh.add(arr.getString(i))
            synchronized(onions) {
                onions.clear()
                onions.addAll(fresh)
            }
        }
        lastReadMs = System.currentTimeMillis()
    }

    /** Record consent for [onion]: write it into the FRESHLY-READ account-data array
     *  (authoritative — never union the in-memory set, or a peer just removed on
     *  another device would be resurrected). Marks consent locally only after a
     *  successful PUT (retried over flaky Tor). Returns true once persisted, or if it
     *  was already recorded. */
    suspend fun record(c: Client, onion: String): Boolean {
        if (contains(onion)) return true // already recorded AND persisted
        // Build the write from a SUCCESSFUL read only — a failed read must never become an
        // empty base set, or we'd PUT {this-onion-only} and the box would drop every other
        // paired contact from its federation allowlist.
        val set = readArrayWithRetry(c) ?: run {
            Log.e(TAG, "record: could not read account-data over Tor; NOT writing $onion (would clobber pairings)")
            return false
        }
        set.add(onion)
        val json = JSONObject().put("onions", JSONArray(set.toList())).toString()
        for (attempt in 1..WRITE_RETRIES) {
            if (runCatching { c.setAccountData(ACCOUNT_DATA_TYPE, json) }.isSuccess) {
                onions.addAll(set)
                Log.i(TAG, "recorded $onion (attempt $attempt)")
                return true
            }
            Log.w(TAG, "record write attempt $attempt failed")
            delay(RETRY_DELAY_MS)
        }
        Log.e(TAG, "gave up persisting $onion after retries")
        return false
    }

    /** Inverse of [record]: drop [onion] from account-data (so the box's reconcile
     *  cuts it from the federation allowlist), dropping in-memory consent ONLY after a
     *  successful PUT — never leave account-data and consent out of sync (a failed
     *  write must NOT silently revoke consent locally; the op must stay re-runnable).
     *  Returns true once persisted, or if the onion was already absent. */
    suspend fun remove(c: Client, onion: String): Boolean {
        // Read with retry; a FAILED read must NOT be treated as "absent" — that would report
        // success + drop local consent while never PUTting the removal, so federation is
        // never actually cut (a half-remove). Only a SUCCESSFUL read that lacks the onion
        // is genuinely "already absent".
        val set = readArrayWithRetry(c) ?: run {
            Log.e(TAG, "remove: could not read account-data over Tor; NOT completing remove of $onion")
            return false
        }
        if (!set.remove(onion)) {
            // Confirmed absent from a good read: nothing to PUT. Keep the in-memory set in sync.
            synchronized(onions) { onions.remove(onion) }
            Log.i(TAG, "$onion not present; nothing to write")
            return true
        }
        val json = JSONObject().put("onions", JSONArray(set.toList())).toString()
        for (attempt in 1..WRITE_RETRIES) {
            if (runCatching { c.setAccountData(ACCOUNT_DATA_TYPE, json) }.isSuccess) {
                synchronized(onions) { onions.remove(onion) }
                Log.i(TAG, "removed $onion (attempt $attempt)")
                return true
            }
            Log.w(TAG, "remove write attempt $attempt failed")
            delay(RETRY_DELAY_MS)
        }
        Log.e(TAG, "gave up removing $onion after retries")
        return false
    }

    /** Read the current `onions` array from account-data.
     *
     *  CRITICAL distinction: returns **null on a READ FAILURE** (the account-data GET threw
     *  — common over flaky Tor) vs an **empty set when genuinely absent** (no such
     *  account-data yet). Callers MUST NOT build a write from a null: doing so would PUT a
     *  set derived from a failed read, and since the box treats `…pairings` as
     *  authoritative, that would de-federate every previously-paired contact. */
    private suspend fun readArray(c: Client): LinkedHashSet<String>? {
        val res = runCatching { c.accountData(ACCOUNT_DATA_TYPE) }
        if (res.isFailure) return null                       // read failed — caller must abort
        val raw = res.getOrNull() ?: return linkedSetOf()    // genuinely absent → empty is correct
        val set = linkedSetOf<String>()
        runCatching {
            JSONObject(raw).optJSONArray("onions")?.let { a ->
                for (i in 0 until a.length()) set.add(a.getString(i))
            }
        }
        return set
    }

    /** Read the account-data set, retrying the READ over flaky Tor. Returns null only if
     *  every read attempt failed — callers must then abort rather than write. */
    private suspend fun readArrayWithRetry(c: Client): LinkedHashSet<String>? {
        for (attempt in 1..WRITE_RETRIES) {
            val set = readArray(c)
            if (set != null) return set
            Log.w(TAG, "account-data read attempt $attempt failed; retrying")
            delay(RETRY_DELAY_MS)
        }
        return null
    }
}
