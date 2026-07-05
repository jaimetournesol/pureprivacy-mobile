package ai.tournesol.pureprivacy.matrix

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.tournesol.pureprivacy.tor.TorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientDelegate
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.EncryptedMessage
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.RoomList
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import java.io.File

/**
 * A row in the chat list.
 *  - [paired]   both people have scanned → a live, openable conversation.
 *  - [invited]  the other person scanned you; you haven't scanned them back yet
 *               (an INCOMING request — tap to scan them and complete the pairing).
 *  - [outgoing] YOU scanned them (you created/joined the room) but the human peer
 *               hasn't joined yet → waiting for them to scan your code. A persistent,
 *               labelled "Pending" row, not a vanished snackbar.
 *
 * Exactly one of paired / invited / outgoing is true for a visible row. [peerId] is the
 * contact's full @name:onion when known (for the read-back identity chip).
 */
data class RoomSummary(
    val id: String,
    val name: String,
    val invited: Boolean = false,
    val paired: Boolean = false,
    val outgoing: Boolean = false,
    /** The contact's full @name:onion, when resolvable (heroes), for read-back. */
    val peerId: String? = null,
    /** Short preview of the latest event ("You: hi", "📎 file.pdf", "📞 Call"). */
    val preview: String? = null,
    /** Timestamp of the latest event (ms); 0 if none. Used to sort the chat list. */
    val ts: Long = 0L,
)
/** Outcome of acting on a scanned/typed contact. [paired] is true only once both
 *  sides have scanned (a live conversation); false means the request is pending. */
data class ConnectResult(val roomId: String, val paired: Boolean)
/** Delivery state of an OWN message, read from the SDK timeline's local echo
 *  (EventTimelineItem.localSendState). A remote message — anyone's already-sent
 *  event — is always [Sent]. Lets the bubble show "sending…/✓/Not sent · retry"
 *  so a slow Tor round-trip never reads as silent data loss. */
enum class SendState { Sending, Sent, Failed }
data class ChatMsg(
    val key: String, val sender: String, val body: String, val mine: Boolean, val ts: Long = 0L,
    // Set for file/image/video/audio messages: the SDK media handle to download, the
    // attachment filename + mime, and whether it's an image (for a future preview).
    val media: org.matrix.rustcomponents.sdk.MediaSource? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val isImage: Boolean = false,
    /** Own-message delivery state from the SDK local echo. Remote msgs = [SendState.Sent]. */
    val sendState: SendState = SendState.Sent,
)
/** A thing worth alerting the user about when they're not looking at the room. */
data class Notif(
    val roomId: String,
    val roomName: String,
    val title: String,
    val text: String,
    val isCall: Boolean = false,
    val isInvite: Boolean = false,
)

/**
 * The real chat brain: Element X's matrix-rust-sdk over embedded Tor. Login, native
 * sliding sync, room list (dynamic adapters), per-room timeline, send. E2EE is
 * handled by the SDK transparently.
 */
object MatrixRepo {
    private const val TAG = "PpMatrix"

    private const val SESSION_ENC = "pp_session_enc"   // encrypted token store
    private const val SESSION_OLD = "pp_session"       // legacy plaintext (migrated away)
    private const val RECOVERY_ACCOUNT_DATA = "ai.tournesol.pureprivacy.recovery"
    private const val FOREGROUND_POLL_MS = 2500L   // snappy while the app is on screen
    private const val BACKGROUND_POLL_MS = 20000L  // gentle backstop in the background/doze
    // Cold-Tor first-login smoothing: retry a transient reach-failure up to this many
    // times, spacing attempts so a just-published onion's circuit has time to build.
    private const val LOGIN_ATTEMPTS = 4
    private const val LOGIN_RETRY_DELAY_MS = 4000L
    /** [QW-perf] Debounce for the onForeground consent read — a resume burst shouldn't
     *  fire a fresh Tor round-trip each time. The authoritative per-sync-cycle
     *  loadConsent is NOT debounced. */
    private const val CONSENT_READ_DEBOUNCE_MS = 60_000L

    /** Consent (scanned-contact onions) + the `…pairings` account-data ops that our
     *  box reconciles into its federation allowlist. Extracted so the consent state
     *  lives in one cohesive place instead of threading through the repo (W3-T3). */
    private val consent = ConsentRepository()

    private var client: Client? = null
    private var syncService: SyncService? = null
    private var roomListService: RoomListService? = null
    private var timeline: Timeline? = null
    // These subscription handles MUST be held for the lifetime of the sync /
    // open room: the SDK cancels the underlying stream task the moment its handle
    // is dropped, which silently stops incremental room-list + timeline updates
    // (the bug where new messages/invites only appeared after an app restart).
    private var roomList: RoomList? = null
    private var roomListResult: RoomListEntriesWithDynamicAdaptersResult? = null
    private var timelineHandle: TaskHandle? = null
    // Self-healing sync. The SyncService does NOT restart itself when it faults
    // (ERROR) or stops (TERMINATED) — which happens on a Tor circuit change, a
    // network blip, or the box restarting. Left unwatched, the phone silently
    // stops receiving room/membership updates AND incoming-call (m.call.member)
    // state until the app is force-killed and relaunched. So we observe its state,
    // restart it with capped backoff, and reconcile on every recovery. This handle
    // owns the state-observer stream task (drop it -> the observer stops firing).
    private var syncStateHandle: TaskHandle? = null
    @Volatile private var syncRestartDelayMs = 0L

    var userId: String = ""
        private set
    var deviceId: String = ""
        private set
    var currentRoom: Room? = null
        private set
    /** The room the user currently has open — suppresses notifications for it. */
    @Volatile var currentRoomId: String? = null
    fun client(): Client? = client

    val rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    val messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val status = MutableStateFlow("")

    /** [H1] Set true when the session is HARD-revoked (a non-soft-logout auth error, or
     *  the sync retry loop exhausting its hard-failure budget). AppViewModel observes
     *  this and routes to Login. Never set on a transient/soft error — those keep
     *  retrying with a valid (possibly refreshed) token. */
    val authExpired = MutableStateFlow(false)
    /** Owns the ClientDelegate callback stream (didReceiveAuthError); dropping it stops
     *  the callback, so we hold it for the client's lifetime. */
    private var clientDelegateHandle: TaskHandle? = null
    /** Consecutive sync hard-failures (ERROR/TERMINATED) since the last RUNNING. Past a
     *  cap we treat the session as dead and route to Login instead of looping forever. */
    @Volatile private var syncHardFailures = 0

    /** Background-notification stream — the foreground service collects this and
     *  posts Android notifications for messages / calls / invites. */
    val notifications = MutableSharedFlow<Notif>(extraBufferCapacity = 32)
    /** Emits a roomId when an active call there ends — used to stop a ringing
     *  callee (caller hung up before answer) and cancel the call notification. */
    val callEnded = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Rooms where a peer currently has an active call (to ring once per call). */
    private val activeCallRooms = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var notifyArmed = false   // don't fire for the initial backfill
    @Volatile private var notifyFromMs = 0L     // only notify for events newer than this
    @Volatile private var pollStarted = false
    @Volatile private var appForeground = false          // drives the adaptive backstop poll
    @Volatile private var appContext: Context? = null   // app context for prefs from sync paths

    private val roomHandles = LinkedHashMap<String, Room>()
    // Per-room RoomInfo subscriptions (id -> handle): the EVENT-DRIVEN incoming-call
    // path. The moment a peer's m.call.member federates in, the SDK fires the listener
    // and we ring — no waiting for the backstop poll. The TaskHandle owns the FFI
    // subscription (and pins the Room), so it survives roomHandles churn.
    private val callSubs = java.util.concurrent.ConcurrentHashMap<String, TaskHandle>()
    private val timelineItems = ArrayList<TimelineItem>()
    // Local-echo resend handles, keyed by ChatMsg.key (the event/transaction id). The
    // SDK hands a SendHandle off each own message's local echo; we stash it so a failed
    // bubble can be tapped to retry via SendHandle.tryResend(). Rebuilt on every timeline
    // diff (handles for now-sent/removed items are dropped). Bounded by the open room.
    private val sendHandles = java.util.concurrent.ConcurrentHashMap<String, org.matrix.rustcomponents.sdk.SendHandle>()

    val isLoggedIn: Boolean get() = client != null

    /** Is there a persisted session to restore? Lets the UI show a splash (not the
     *  empty login form) on a cold start for a returning user. */
    fun hasSavedSession(ctx: Context): Boolean =
        runCatching { sessionPrefs(ctx).getString("at", null) != null }.getOrDefault(false)

    private fun builder(ctx: Context, homeserverUrl: String?): ClientBuilder {
        appContext = ctx.applicationContext
        val base = File(ctx.filesDir, "matrix").apply { mkdirs() }
        val session = File(base, "session").apply { mkdirs() }
        val cache = File(base, "cache").apply { mkdirs() }
        var b = ClientBuilder()
            .sessionPaths(session.absolutePath, cache.absolutePath)
            .proxy(TorManager.proxyUrl)                 // route every request through Tor
            .disableSslVerification()                   // onion box uses a self-signed/plain endpoint
            // PIN native sliding sync — do NOT DISCOVER it. DISCOVER_NATIVE makes a
            // homeserver round-trip DURING ClientBuilder.build(); over flaky Tor that
            // call intermittently hangs forever (no timeout), so login/restore never
            // completes and the app sits on "Restoring your session…" (seen live: box
            // healthy + onion reachable + Tor 100%, yet build() never returned). Our box
            // is always tuwunel, which always speaks native simplified sliding sync, so
            // the discovery is pure risk with no upside — pin NATIVE and skip the call.
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            // Key backup + cross-signing (option A, see ensureKeyBackup): cross-signing
            // auto-trusts our own devices (no manual verification UX), backups let a
            // re-login / new device recover room keys. ONE_SHOT downloads the entire key
            // backup as soon as recovery unlocks it — the right strategy for restoring
            // full history on a new device (vs lazy per-message download over slow Tor).
            .autoEnableCrossSigning(true)
            .autoEnableBackups(true)
            .backupDownloadStrategy(uniffi.matrix_sdk.BackupDownloadStrategy.ONE_SHOT)
            // [H1] Re-persist on every token rotation. The SDK refreshes the access (and
            // sometimes refresh) token in the background; without a session delegate the
            // rotated token only lived in memory, so the NEXT cold restore replayed a
            // stale token and 401'd. saveSessionInKeychain fires on each refresh — write
            // it straight to the encrypted prefs so restore always uses the live token.
            .setSessionDelegate(sessionDelegate(ctx))
        if (homeserverUrl != null) b = b.homeserverUrl(homeserverUrl)
        return b
    }

    /** [H1] The SDK's session/token-rotation delegate. saveSessionInKeychain is called
     *  on every token refresh (and once at login) — we re-persist to the encrypted
     *  prefs so a rotated token is never lost. retrieveSessionFromKeychain lets the
     *  SDK's cross-process refresh read back the current session. We do NOT call
     *  disableAutomaticTokenRefresh — we WANT the SDK to keep refreshing; this delegate
     *  just makes the refresh durable. */
    private fun sessionDelegate(ctx: Context): ClientSessionDelegate {
        val app = ctx.applicationContext
        return object : ClientSessionDelegate {
            override fun saveSessionInKeychain(session: Session) {
                runCatching { persist(app, session) }
                    .onSuccess { Log.i(TAG, "session delegate: re-persisted rotated token") }
                    .onFailure { Log.w(TAG, "session delegate: persist failed: ${it.message}") }
            }
            override fun retrieveSessionFromKeychain(userId: String): Session {
                val p = sessionPrefs(app)
                val at = p.getString("at", null)
                    ?: throw IllegalStateException("no stored session for $userId")
                val hs = p.getString("hs", null)
                    ?: throw IllegalStateException("no stored homeserver for $userId")
                val ssv = runCatching { SlidingSyncVersion.valueOf(p.getString("ssv", "NATIVE")!!) }
                    .getOrDefault(SlidingSyncVersion.NATIVE)
                return Session(
                    at, p.getString("rt", null), p.getString("uid", userId)!!,
                    p.getString("did", "")!!, hs, p.getString("oauth", null), ssv
                )
            }
        }
    }

    /** Delete the on-disk Matrix session + crypto store. A stale store from a
     *  previous account/device causes the SDK's MismatchedAccount crypto error on a
     *  fresh login, so we always start a sign-in from clean state. */
    private fun wipeStore(ctx: Context) {
        runCatching { File(ctx.filesDir, "matrix").deleteRecursively() }
    }

    /** [H1] Register the client delegate so a HARD auth error (token revoked / account
     *  invalidated server-side — anything that is NOT a recoverable soft-logout) tears
     *  the session down and routes the user to Login instead of retrying a dead token
     *  forever. A soft-logout (isSoftLogout=true) is the SDK's "your token rotated, I'll
     *  refresh" path — we leave that alone, the session delegate keeps it persisted. */
    private fun registerClientDelegate(ctx: Context, c: Client) {
        val app = ctx.applicationContext
        runCatching { clientDelegateHandle?.cancel() }
        clientDelegateHandle = runCatching {
            c.setDelegate(object : ClientDelegate {
                override fun didReceiveAuthError(isSoftLogout: Boolean) {
                    Log.w(TAG, "didReceiveAuthError(softLogout=$isSoftLogout)")
                    if (isSoftLogout) {
                        // Recoverable: the SDK refreshes the token; the session delegate
                        // re-persists it. Do NOT wipe — that would log out a valid user.
                        return
                    }
                    // Hard revoke: stop fighting it, clear the stored session, route to Login.
                    handleHardAuthError(app)
                }
                override fun onBackgroundTaskErrorReport(
                    taskName: String,
                    error: uniffi.matrix_sdk_common.BackgroundTaskFailureReason
                ) {
                    Log.w(TAG, "background task error in $taskName: $error")
                }
            })
        }.getOrElse { Log.w(TAG, "setDelegate failed: ${it.message}"); null }
    }

    /** [H1] A hard, unrecoverable auth failure: stop the sync retry loop, wipe the stored
     *  session + crypto store, and flip [authExpired] so AppViewModel routes to Login.
     *  Idempotent — guarded by authExpired so concurrent callers (delegate + retry-loop
     *  escalation) don't double-tear-down. Never called for a transient/soft error. */
    private fun handleHardAuthError(ctx: Context) {
        if (authExpired.value) return
        Log.w(TAG, "hard auth error — clearing session and routing to Login")
        runCatching { syncStateHandle?.cancel() }; syncStateHandle = null
        val ss = syncService                              // stop() is suspend — do it off-thread
        runCatching { sessionPrefs(ctx).edit().clear().apply() }
        runCatching { ctx.getSharedPreferences(SESSION_OLD, Context.MODE_PRIVATE).edit().clear().apply() }
        wipeStore(ctx)                                    // [H1] also delete the on-disk crypto store so it matches the doc/report (login() also wipes it before re-auth)
        client = null; syncService = null
        scope.launch { runCatching { ss?.stop() } }
        status.value = "Signed out — please sign in again."
        authExpired.value = true
    }

    suspend fun login(ctx: Context, homeserverUrl: String, user: String, pass: String) {
        status.value = "Connecting over Tor…"
        wipeStore(ctx)                                  // clean crypto store for this sign-in
        // Drop the cached recovery key — a fresh sign-in (possibly a different account)
        // should re-read the authoritative key from the box's account data, not reuse a
        // stale one. ensureKeyBackup then recovers this new device's keys from backup.
        runCatching { sessionPrefs(ctx).edit().remove("rk").apply() }
        val c = builder(ctx, homeserverUrl).build()
        client = c
        authExpired.value = false                       // [H1] fresh sign-in clears any prior expiry
        registerClientDelegate(ctx, c)                  // [H1] watch for hard auth errors
        status.value = "Signing in…"
        // First sign-in on a freshly-provisioned box hits a COLD Tor circuit to a just-
        // published onion descriptor: the POST /login intermittently times out on the
        // first attempt or two even though the box is perfectly healthy (seen live — the
        // box answered /login in ~2s from a warm circuit, yet the app's first try failed
        // and the user had to tap Retry / relaunch). Retry a transient reach-failure a
        // few times over the slow circuit before surfacing it, so a friend's very first
        // sign-in "just works". A wrong password (isAuthError) is NOT transient — rethrow
        // it at once so we never loop on bad credentials.
        var attempt = 0
        while (true) {
            attempt++
            try {
                c.login(user, pass, "PurePrivacy Android", null)
                break
            } catch (t: Throwable) {
                if (ai.tournesol.pureprivacy.util.isAuthError(t) || attempt >= LOGIN_ATTEMPTS) throw t
                Log.w(TAG, "login attempt $attempt failed (transient over Tor), retrying: ${t.message}")
                status.value = "Signing in… still connecting over Tor"
                kotlinx.coroutines.delay(LOGIN_RETRY_DELAY_MS)
            }
        }
        userId = runCatching { c.userId() }.getOrDefault("@$user")
        runCatching {
            val s = c.session()
            deviceId = s.deviceId
            persist(ctx, s)
        }
        Log.i(TAG, "logged in as $userId (device $deviceId)")
        status.value = "Syncing…"
    }

    /** Restore a persisted session instead of re-logging in (avoids crypto-store
     *  mismatch + survives app restarts). Returns false if no saved session. */
    suspend fun tryRestore(ctx: Context): Boolean {
        if (client != null) return true
        val p = sessionPrefs(ctx)
        val at = p.getString("at", null) ?: return false
        val hs = p.getString("hs", null) ?: return false
        status.value = "Restoring session over Tor…"
        val c = builder(ctx, hs).build()
        val ssv = runCatching { SlidingSyncVersion.valueOf(p.getString("ssv", "NATIVE")!!) }
            .getOrDefault(SlidingSyncVersion.NATIVE)
        val s = Session(
            at, p.getString("rt", null), p.getString("uid", "")!!,
            p.getString("did", "")!!, hs, p.getString("oauth", null), ssv
        )
        c.restoreSession(s)
        client = c
        authExpired.value = false                       // [H1] a successful restore is a live session
        registerClientDelegate(ctx, c)                  // [H1] watch for hard auth errors
        userId = s.userId
        deviceId = s.deviceId
        Log.i(TAG, "restored session for $userId (device $deviceId)")
        return true
    }

    private fun persist(ctx: Context, s: Session) {
        sessionPrefs(ctx).edit()
            .putString("at", s.accessToken)
            .putString("rt", s.refreshToken)
            .putString("uid", s.userId)
            .putString("did", s.deviceId)
            .putString("hs", s.homeserverUrl)
            .putString("oauth", s.oauthData)
            .putString("ssv", s.slidingSyncVersion.name)
            .apply()
    }

    /** The session store — access token, refresh token, device id — encrypted at rest
     *  with an AES-256 key held in the Android Keystore. Migrates the old plaintext
     *  prefs once, then wipes them. If the keyset is ever corrupted (e.g. partial data
     *  clear) we reset it and recreate rather than crash on every launch. */
    private fun sessionPrefs(ctx: Context): SharedPreferences {
        val prefs = runCatching { buildEncryptedPrefs(ctx) }.getOrElse {
            Log.w(TAG, "encrypted prefs unavailable, resetting keyset: ${it.message}")
            ctx.deleteSharedPreferences(SESSION_ENC)
            buildEncryptedPrefs(ctx)
        }
        migrateLegacySession(ctx, prefs)
        return prefs
    }

    private fun buildEncryptedPrefs(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, SESSION_ENC, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Move tokens out of the legacy plaintext prefs into the encrypted store (once),
     *  then clear the plaintext copy so the access token never lingers unencrypted. */
    private fun migrateLegacySession(ctx: Context, enc: SharedPreferences) {
        val old = ctx.getSharedPreferences(SESSION_OLD, Context.MODE_PRIVATE)
        if (old.getString("at", null) == null) return
        if (enc.getString("at", null) == null) {
            val e = enc.edit()
            for ((k, v) in old.all) if (v is String) e.putString(k, v)
            e.apply()
            Log.i(TAG, "migrated session into encrypted prefs")
        }
        old.edit().clear().apply()
    }

    /** Option A automatic key backup. Cross-signing + key backup are auto-enabled in the
     *  client builder; this sets up "recovery" so the room-key backup survives a re-login
     *  or a brand-new device:
     *   - first device for an account: create the recovery key, then stash it in the
     *     encrypted prefs AND in account data on the user's own box (their trusted server);
     *   - a later device / re-login: fetch that key and recover(), pulling the room keys
     *     down from backup so message history decrypts.
     *  Trade-off the user accepted (option A): the recovery key lives in plaintext account
     *  data on the box. The box is the user's own onion server and already stores the
     *  encrypted history, so this gives zero-friction recovery without ever prompting. */
    private suspend fun ensureKeyBackup() {
        val ctx = appContext ?: return
        val c = client ?: return
        val enc = c.encryption()
        // recoveryState is UNKNOWN until the first sync resolves the crypto state.
        var waited = 0
        while (enc.recoveryState() == RecoveryState.UNKNOWN && waited < 30) {
            kotlinx.coroutines.delay(1000); waited++
        }
        val stored = readStoredRecoveryKey(c, ctx)
        if (stored != null) {
            // We (or our box) already hold a recovery key → make sure THIS device is
            // hooked up to the backup so it can decrypt history. Idempotent if already done.
            val rs = enc.recoveryState()
            if (rs == RecoveryState.INCOMPLETE || rs == RecoveryState.DISABLED) {
                runCatching { enc.recover(stored) }
                    .onSuccess { Log.i(TAG, "key backup: recovered room keys from backup") }
                    .onFailure { Log.w(TAG, "key backup: recover failed: ${it.message}") }
            }
            persistRecoveryKey(c, ctx, stored)   // ensure both copies (prefs + box) exist
            return
        }
        // No recovery key anywhere → first device for this account. Create backup + key.
        if (enc.recoveryState() != RecoveryState.ENABLED) {
            val key = runCatching {
                enc.enableRecovery(true, null, object : EnableRecoveryProgressListener {
                    override fun onUpdate(status: EnableRecoveryProgress) {}
                })
            }.getOrElse { Log.w(TAG, "key backup: enableRecovery failed: ${it.message}"); return }
            persistRecoveryKey(c, ctx, key)
            Log.i(TAG, "key backup: created recovery + backup (first device)")
        }
    }

    /** Read the recovery key: this device's encrypted prefs first, else account data on
     *  the box (so a fresh install/device that has neither still finds it). */
    private suspend fun readStoredRecoveryKey(c: Client, ctx: Context): String? {
        sessionPrefs(ctx).getString("rk", null)?.let { return it }
        return runCatching { c.accountData(RECOVERY_ACCOUNT_DATA) }.getOrNull()
            ?.let { runCatching { org.json.JSONObject(it).optString("key").ifBlank { null } }.getOrNull() }
    }

    private suspend fun persistRecoveryKey(c: Client, ctx: Context, key: String) {
        sessionPrefs(ctx).edit().putString("rk", key).apply()
        runCatching {
            if (c.accountData(RECOVERY_ACCOUNT_DATA) == null)
                c.setAccountData(RECOVERY_ACCOUNT_DATA, org.json.JSONObject().put("key", key).toString())
        }
    }

    suspend fun startSync() {
        val c = client ?: error("not logged in")
        runCatching { loadConsent() }   // restore who we've already scanned
        val ss = c.syncService().finish()
        syncService = ss
        ss.start()
        // Watch the sync stream and self-heal. RUNNING -> reset backoff + reconcile
        // any room/pairing/call state that drifted while we were down. ERROR /
        // TERMINATED -> restart with capped exponential backoff. IDLE/OFFLINE are
        // SDK-managed (it auto-resumes from OFFLINE when the network returns), so we
        // don't fight them. Replaces the previous fire-once start() that left the app
        // stuck on stale state after any interruption (the "needs an app restart" bug).
        runCatching { syncStateHandle?.cancel() }
        syncStateHandle = ss.state(object : org.matrix.rustcomponents.sdk.SyncServiceStateObserver {
            override fun onUpdate(state: org.matrix.rustcomponents.sdk.SyncServiceState) {
                Log.i(TAG, "syncService state=$state")
                when (state) {
                    org.matrix.rustcomponents.sdk.SyncServiceState.RUNNING -> {
                        syncRestartDelayMs = 0L
                        syncHardFailures = 0                 // [H1] a healthy cycle resets the budget
                        if (status.value != "Connected") status.value = "Connected"
                        scope.launch {
                            // Re-read consent from account-data first so a removal (here
                            // or on another device) propagates and the consent-gated hide
                            // in rebuildRooms takes effect this cycle.
                            runCatching { loadConsent() }
                            runCatching { autoAcceptMutual() }
                            runCatching { reInviteStalePeers() }
                            runCatching { rebuildRooms() }
                            runCatching { reconcileCallSubs() }
                            runCatching { checkNotifs() }
                        }
                    }
                    org.matrix.rustcomponents.sdk.SyncServiceState.ERROR,
                    org.matrix.rustcomponents.sdk.SyncServiceState.TERMINATED -> {
                        // [H1] ERROR/TERMINATED is ALWAYS treated as transient and retried
                        // with capped backoff — that's the self-heal this loop exists for.
                        // We must NEVER wipe a valid session on a sync fault: the SDK
                        // surfaces an unreachable box over Tor as ERROR/TERMINATED (not
                        // OFFLINE), and the project's own notes document multi-minute Tor
                        // federation stalls, so a fault-count escalation here would
                        // destroy a perfectly valid session on a benign network outage.
                        // The authoritative hard-auth signal is the delegate's
                        // didReceiveAuthError(isSoftLogout=false) (revoked token / account
                        // invalidated server-side), which is what tears the session down.
                        // syncHardFailures is kept for diagnostics only (reset on RUNNING).
                        syncHardFailures++
                        if (syncHardFailures > 0 && syncHardFailures % 12 == 0) {
                            Log.w(TAG, "sync still faulting after $syncHardFailures consecutive errors — continuing to retry over Tor")
                        }
                        val delay = syncRestartDelayMs.coerceAtLeast(1000L)
                        syncRestartDelayMs = (delay * 2).coerceAtMost(30000L)
                        status.value = "Reconnecting over Tor…"
                        scope.launch {
                            kotlinx.coroutines.delay(delay)
                            Log.i(TAG, "syncService restart after ${delay}ms (was $state)")
                            runCatching { ss.start() }
                        }
                    }
                    else -> {}   // IDLE / OFFLINE — SDK-managed
                }
            }
        })
        val rls = ss.roomListService()
        roomListService = rls
        val rl = rls.allRooms()
        roomList = rl                       // HOLD: dropping the RoomList cancels its stream
        val result = rl.entriesWithDynamicAdapters(
            200u,
            object : RoomListEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                    applyRoomUpdates(roomEntriesUpdate)
                }
            }
        )
        roomListResult = result            // HOLD: owns the entries-stream TaskHandle
        // populate: no extra filtering, just show every room we're in
        result.controller().setFilter(RoomListEntriesDynamicFilterKind.All(emptyList()))
        status.value = "Connected"
        // arm notifications: only events from ~now on are "new" (avoids backfill).
        notifyFromMs = System.currentTimeMillis()
        scope.launch { kotlinx.coroutines.delay(3000); notifyArmed = true }
        // Set up / restore the auto-managed key backup (option A) once sync is running,
        // so a re-login or new device recovers room keys and decrypts history.
        scope.launch { runCatching { ensureKeyBackup() } }
        // Sliding sync delivers events to the local store in ~real time and the
        // room-list listener (applyRoomUpdates) fires checkNotifs the instant a new
        // message reorders a room — which covers the common case with no polling.
        // The one gap is a message to the already-top room (no reorder), so we keep a
        // *backstop* poll — but adaptively: snappy while the user is in the app, and
        // slow when backgrounded so we're not waking the CPU every 2.5s in doze (the
        // real battery drain). Event receipt isn't delayed — only how fast a
        // no-reorder message surfaces as a notification (≤ the background interval).
        if (!pollStarted) {
            pollStarted = true
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(if (appForeground) FOREGROUND_POLL_MS else BACKGROUND_POLL_MS)
                    runCatching { checkNotifs() }
                    // Self-correct room + pairing state even when nothing reordered
                    // the room list — a peer joining an existing room (-> "paired") or
                    // an invite still waiting to auto-accept won't always reorder, so
                    // the room-list listener never fires. autoAcceptMutual is cheap
                    // (cached membership) and matters even backgrounded, so the chat is
                    // already live when the app is opened.
                    runCatching { autoAcceptMutual() }
                    // Keep re-driving any outgoing invite that hasn't landed yet (a fresh
                    // peer's box can take longer than ensureInvited's initial burst to become
                    // reachable over Tor). Idempotent + survives restarts. Matters even
                    // backgrounded so pairing converges without the app open.
                    runCatching { reInviteStalePeers() }
                    // The full rebuild (which recomputes "paired" + refreshes previews)
                    // only matters while the list is on screen.
                    if (appForeground) runCatching { rebuildRooms() }
                }
            }
        }
    }

    /** Nudge the backstop poll the moment the app comes back to the foreground, so
     *  the chat list / previews refresh immediately instead of after a slow tick. */
    fun onForeground(foreground: Boolean) {
        appForeground = foreground
        if (foreground) scope.launch {
            // Refresh consent from account-data on resume so a removal made on another
            // device (or box-side) is reflected the moment the app is opened — then
            // rebuild so the consent-gated hide/show takes effect. loadConsent keeps the
            // existing set on a failed read, so a flaky Tor read never drops a contact.
            // (Foreground-only — not per poll-tick — keeps this cheap.)
            // [QW-perf] Debounce: skip the Tor read if we read consent < ~60s ago (a
            // resume burst / rapid app switching shouldn't fire a fresh round-trip each
            // time). The authoritative per-sync-cycle loadConsent is unaffected.
            if (System.currentTimeMillis() - consent.lastReadMs >= CONSENT_READ_DEBOUNCE_MS)
                runCatching { loadConsent() }
            runCatching { rebuildRooms() }
            runCatching { checkNotifs() }; runCatching { refreshPreviews() }
        }
    }

    private fun applyRoomUpdates(updates: List<RoomListEntriesUpdate>) {
        synchronized(roomHandles) {
            val list = roomHandles.values.toMutableList()
            for (u in updates) {
                when (u) {
                    is RoomListEntriesUpdate.Append -> list.addAll(u.values)
                    is RoomListEntriesUpdate.PushBack -> list.add(u.value)
                    is RoomListEntriesUpdate.PushFront -> list.add(0, u.value)
                    is RoomListEntriesUpdate.Insert -> list.add(u.index.toInt(), u.value)
                    is RoomListEntriesUpdate.Set -> if (u.index.toInt() < list.size) list[u.index.toInt()] = u.value
                    is RoomListEntriesUpdate.Remove -> if (u.index.toInt() < list.size) list.removeAt(u.index.toInt())
                    is RoomListEntriesUpdate.Reset -> { list.clear(); list.addAll(u.values) }
                    is RoomListEntriesUpdate.Clear -> list.clear()
                    is RoomListEntriesUpdate.PopBack -> if (list.isNotEmpty()) list.removeAt(list.size - 1)
                    is RoomListEntriesUpdate.PopFront -> if (list.isNotEmpty()) list.removeAt(0)
                    is RoomListEntriesUpdate.Truncate -> while (list.size > u.length.toInt()) list.removeAt(list.size - 1)
                }
            }
            roomHandles.clear()
            list.forEach { r -> runCatching { roomHandles[r.id()] = r } }
        }
        // Off-thread: accept any newly-arrived invite from a contact we've also
        // scanned (mutual consent), then recompute the visible chat list. Deciding
        // "paired" inspects members (to exclude the @conduit bot) — a suspend call,
        // so it can't run inside the lock.
        scope.launch { autoAcceptMutual(); rebuildRooms(); checkNotifs(); reconcileCallSubs() }
    }

    /** The tuwunel server bot is a member of every room it administers; it must not
     *  count as the human peer when deciding names or whether a chat is live. */
    private fun isServerBot(uid: String): Boolean = uid.startsWith("@conduit:")

    /** Is the human peer actually joined? (Both joined = a live, mutually-scanned
     *  chat.) Counts a joined member who is neither me nor the server bot. */
    private suspend fun peerJoined(r: Room): Boolean {
        // Prefer the cached member list; but on a fresh device the cache is empty
        // until the first networked fetch — and for a *federated* room (one hosted
        // on the peer's box, e.g. when they created the DM) that networked fetch is
        // the only way to learn the peer is joined. So when the cached pass finds
        // nobody, fall through to members() (which fetches over Tor). Without this a
        // reinstall / second device would never re-surface an existing chat.
        if (scanForJoinedPeer(runCatching { r.membersNoSync() }.getOrNull())) return true
        // [QW-perf] members() is a NETWORKED fetch over Tor — bound it so a hung circuit
        // can't stall rebuildRooms (and the whole visible chat list) indefinitely. On a
        // timeout we just fall back to "peer not seen joined yet" (false); the next sync
        // cycle / rebuild re-tries, so a slow circuit degrades gracefully instead of
        // freezing the list.
        val networked = kotlinx.coroutines.withTimeoutOrNull(15_000) {
            runCatching { r.members() }.getOrNull()
        }
        return scanForJoinedPeer(networked)
    }

    /** True if [iter] yields a joined member who is neither me nor the server bot. */
    private fun scanForJoinedPeer(iter: org.matrix.rustcomponents.sdk.RoomMembersIterator?): Boolean {
        if (iter == null) return false
        while (true) {
            val chunk = runCatching { iter.nextChunk(64u) }.getOrNull()
            if (chunk.isNullOrEmpty()) break
            for (m in chunk) {
                if (m.userId == userId || isServerBot(m.userId)) continue
                if (m.membership is MembershipState.Join) return true
            }
        }
        return false
    }

    /** A room with no human counterpart — its only members (per the local cache)
     *  are us + the @conduit server bot (the box's auto-created Admin/control room),
     *  or it's a stray room we created but never invited anyone to. Such a room must
     *  NEVER appear as a chat (paired / invited / OUTGOING). Without this guard the
     *  "pending outgoing" rule (JOINED && !peerJoined) wrongly surfaces the @conduit
     *  Admin Room as "Pending · waiting for them to scan your code" the moment a user
     *  signs in — before they've added anyone. Uses the cached member list only: a
     *  bot-only / empty room is always LOCAL so its members are cached; a federated
     *  peer room whose cache isn't warm yet is NOT this, so we don't hide it. */
    private suspend fun lacksHumanPeer(r: Room): Boolean {
        val iter = runCatching { r.membersNoSync() }.getOrNull() ?: return false
        var sawAny = false
        var sawHuman = false
        while (true) {
            val chunk = runCatching { iter.nextChunk(64u) }.getOrNull()
            if (chunk.isNullOrEmpty()) break
            for (m in chunk) {
                sawAny = true
                if (m.userId != userId && !isServerBot(m.userId)) sawHuman = true
            }
        }
        return sawAny && !sawHuman
    }

    /** Rebuild the chat list with accurate per-room state (incl. the bot-aware
     *  paired check). Runs in a coroutine; assigns `rooms` once when done. */
    private suspend fun rebuildRooms() {
        val handles = synchronized(roomHandles) { roomHandles.values.toList() }
        rooms.value = handles.map { r ->
            val mem = runCatching { r.membership() }.getOrNull()
            val (preview, ts) = latestPreview(r)
            // a live conversation = I'm in AND the human peer is in too — which only
            // happens once both have scanned each other.
            val peerIn = mem == Membership.JOINED && peerJoined(r)
            // Consent-gated hide: a peer we've silently removed is dropped from
            // the consent set (account-data), so the row must disappear with no
            // federated leave. The `null` fallback (heroes not warm yet) avoids
            // hiding a live chat during hero warm-up — gating only kicks in once we
            // can actually read the peer's onion.
            val peerOnion = peerId(r)?.substringAfter(":", "")?.trim()
            val paired = peerIn && (peerOnion == null || peerOnion in consent)
            RoomSummary(
                r.id(), roomName(r),
                invited = mem == Membership.INVITED,
                paired = paired,
                // I'm joined but the peer isn't (and they didn't invite me): I scanned
                // them and I'm waiting for them to scan me back → a pending OUTGOING row.
                // Excludes the box's @conduit Admin Room / stray empty rooms (no human
                // peer), which must never show as a chat.
                outgoing = mem == Membership.JOINED && !peerIn && !lacksHumanPeer(r),
                peerId = peerId(r),
                preview = preview,
                ts = ts,
            )
        // Invites first (actionable), then pending outgoing, then most-recently-active.
        }.sortedWith(
            compareByDescending<RoomSummary> { it.invited }
                .thenByDescending { it.outgoing }
                .thenByDescending { it.ts }
        )
        Log.i(TAG, "rebuildRooms: ${rooms.value.size} rooms (" +
            "${rooms.value.count { it.paired }} paired, ${rooms.value.count { it.invited }} invited, " +
            "${rooms.value.count { it.outgoing }} pending-outgoing)")
    }

    /** Cheap refresh of just the chat-list previews + timestamps (and re-sort) from the
     *  cached latest events — no membership re-fetch. Keeps the list current when a new
     *  message arrives to a room that doesn't reorder. Preserves name/paired/invited. */
    private suspend fun refreshPreviews() {
        val current = rooms.value
        if (current.isEmpty()) return
        val handles = synchronized(roomHandles) { LinkedHashMap(roomHandles) }
        var changed = false
        val updated = current.map { rs ->
            val r = handles[rs.id] ?: return@map rs
            val (preview, ts) = latestPreview(r)
            if (preview != rs.preview || ts != rs.ts) { changed = true; rs.copy(preview = preview, ts = ts) } else rs
        }
        if (changed) rooms.value =
            updated.sortedWith(compareByDescending<RoomSummary> { it.invited }.thenByDescending { it.ts })
    }

    /** A short preview (text + timestamp ms) of a room's latest event, for the chat
     *  list. Mirrors the chat bubble's attachment icons; prefixes own messages with
     *  "You: ". Returns (null, 0) when there's nothing to show. */
    private suspend fun latestPreview(room: Room): Pair<String?, Long> {
        val lev = runCatching { room.latestEvent() }.getOrNull() ?: return null to 0L
        val remote = lev as? LatestEventValue.Remote ?: return null to tsOf(lev)
        val text = when (val c = remote.content) {
            is TimelineItemContent.MsgLike -> {
                val k = c.content.kind
                if (k is MsgLikeKind.Message) {
                    val mc = k.content
                    val body = when (val mt = mc.msgType) {
                        is org.matrix.rustcomponents.sdk.MessageType.File ->
                            "📎 ${mt.content.filename ?: mc.body}"
                        is org.matrix.rustcomponents.sdk.MessageType.Image ->
                            "🖼️ ${mt.content.filename ?: mc.body}"
                        is org.matrix.rustcomponents.sdk.MessageType.Video -> "🎞️ ${mc.body}"
                        is org.matrix.rustcomponents.sdk.MessageType.Audio -> "🎵 ${mc.body}"
                        else -> mc.body.replace("\n", " ").trim()
                    }
                    if (remote.isOwn) "You: $body" else body
                } else null
            }
            is TimelineItemContent.RtcNotification, is TimelineItemContent.CallInvite -> "📞 Call"
            else -> null
        }
        return text to tsOf(lev)
    }

    /** Look at each room's latest event and emit a notification for anything new
     *  the user isn't already looking at (a message, an incoming call, an invite). */
    private suspend fun checkNotifs() {
        val handles = synchronized(roomHandles) { LinkedHashMap(roomHandles) }
        for ((id, room) in handles) {
            // Incoming-call detection (backstop poll; the live, low-latency path is the
            // per-room RoomInfo subscription set up in reconcileCallSubs). Shared logic
            // lives in evaluateRoomCall so both paths ring identically.
            runCatching {
                val hasCall = room.hasActiveRoomCall()
                val parts = if (hasCall) room.activeRoomCallParticipants() else emptyList()
                evaluateRoomCall(room, hasCall, parts)
            }
            val lev = runCatching { room.latestEvent() }.getOrNull() ?: continue
            val key = keyOf(lev) ?: continue
            if (lastSeen[id] == key) continue          // already handled this event
            lastSeen[id] = key
            if (!notifyArmed) continue
            if (id == currentRoomId) continue          // user is looking at it
            if (tsOf(lev) < notifyFromMs) continue     // backfill / history, not new
            val n = toNotif(id, room, lev) ?: continue
            Log.i(TAG, "notify: ${n.title} (${id})")
            notifications.emit(n)
        }
    }

    /** Ring / stop-ring for one room from its current call state — shared by the
     *  backstop poll and the live RoomInfo subscription. activeCallRooms is a
     *  synchronized set, so a concurrent add() from both paths rings exactly once. */
    private suspend fun evaluateRoomCall(room: Room, hasCall: Boolean, participants: List<String>) {
        val id = runCatching { room.id() }.getOrNull() ?: return
        // Consent-gated ring: never ring for a peer we've removed (silently or by
        // notify). Their onion is gone from the consent set, so the row is hidden —
        // an incoming call from them must not resurface as a phantom ring. Gate on
        // the human caller's onion; the `null` fallback (no human participant) keeps
        // the existing "no one to ring for" behaviour.
        val callerOnion = participants
            .firstOrNull { it != userId && !isServerBot(it) }
            ?.substringAfter(":", "")?.trim()
        val peerConsented = callerOnion == null || callerOnion in consent
        val peerInCall = hasCall && peerConsented && participants.any { it != userId && !isServerBot(it) }
        // Multi-device: if MY account is already in the call (this or another of my
        // devices answered), don't ring — and stop any ring in progress.
        val meInCall = participants.any { it == userId }
        val shouldRing = peerInCall && !meInCall
        if (shouldRing && activeCallRooms.add(id)) {
            Log.i(TAG, "ring START for $id (peerInCall=$peerInCall meInCall=$meInCall parts=${participants.size})")
            // Ring even when this very chat is open on screen. The `currentRoomId` guard
            // is right for MESSAGES (no need to notify about a chat you're reading) but
            // wrong for CALLS — calling someone who happens to have your conversation
            // open would otherwise show them nothing. activeCallRooms already dedups to
            // one ring per call, and clearMyCallMembership() stops the stale-membership
            // ghost that previously turned an open chat into a phantom perpetual ring.
            if (notifyArmed) {
                val who = roomName(room)
                notifications.emit(Notif(id, who, "📞 Incoming call", "$who is calling — over Tor", isCall = true))
            }
        } else if (!shouldRing && activeCallRooms.remove(id)) {
            Log.i(TAG, "ring STOP for $id (reason=${if (meInCall) "answered-elsewhere" else "peer-left"} peerInCall=$peerInCall meInCall=$meInCall)")
            callEnded.emit(id)   // peer left, or I answered (here or on another device)
        }
    }

    /** Retract THIS device's RTC call membership (write an empty `m.call.member` state)
     *  when we leave a call. Element Call normally manages this, but over flaky Tor /
     *  an abrupt close the membership can linger — and a stale OWN membership is poison:
     *  our own `evaluateRoomCall` then reads `meInCall=true` forever, so the next
     *  incoming call never rings; the peer also sees a ghost participant that makes
     *  Element Call refuse to start a new call. The state_key is the per-device RTC key
     *  `_<userId>_<deviceId>_m.call` (matches what Element Call writes). Fire-and-forget
     *  on the app scope so it still completes after the call activity is destroyed. */
    fun clearMyCallMembership(roomId: String) {
        val c = client ?: return
        val uid = userId; val did = deviceId
        if (uid.isBlank() || did.isBlank()) return
        scope.launch {
            val room = runCatching { c.getRoom(roomId) }.getOrNull() ?: return@launch
            val stateKey = "_${uid}_${did}_m.call"
            runCatching {
                room.sendStateEventRaw("org.matrix.msc3401.call.member", stateKey, "{}")
            }.onSuccess { Log.i(TAG, "cleared my call membership in $roomId") }
                .onFailure { Log.w(TAG, "clearMyCallMembership failed: ${it.message}") }
        }
    }

    /** Keep a RoomInfo-update subscription on every room so an incoming call rings the
     *  moment the peer's call membership federates in — the event-driven path that
     *  removes the poll-cadence race. We keep one handle per room id (it owns the FFI
     *  subscription and pins its Room, so it survives roomHandles being rebuilt), and
     *  drop it when the room leaves the list. Called after every applyRoomUpdates. */
    private fun reconcileCallSubs() {
        val current = synchronized(roomHandles) { LinkedHashMap(roomHandles) }
        for ((id, room) in current) {
            if (callSubs.containsKey(id)) continue
            val handle = runCatching {
                room.subscribeToRoomInfoUpdates(object : RoomInfoListener {
                    override fun call(roomInfo: RoomInfo) {
                        scope.launch {
                            runCatching {
                                evaluateRoomCall(room, roomInfo.hasRoomCall, roomInfo.activeRoomCallParticipants)
                            }
                        }
                    }
                })
            }.getOrNull()
            if (handle != null) { callSubs[id] = handle; Log.i(TAG, "call-state sub armed for $id") }
        }
        for (id in callSubs.keys.toList()) {
            if (!current.containsKey(id)) runCatching { callSubs.remove(id)?.cancel() }
        }
    }

    private fun keyOf(lev: LatestEventValue): String? = when (lev) {
        is LatestEventValue.Remote -> "r:${lev.timestamp}:${lev.sender}"
        is LatestEventValue.RemoteInvite -> "i:${lev.timestamp}:${lev.inviter}"
        else -> null
    }

    private fun tsOf(lev: LatestEventValue): Long = when (lev) {
        is LatestEventValue.Remote -> lev.timestamp.toLong()
        is LatestEventValue.RemoteInvite -> lev.timestamp.toLong()
        else -> 0L
    }

    private fun toNotif(id: String, room: Room, lev: LatestEventValue): Notif? {
        val name = roomName(room)
        return when (lev) {
            is LatestEventValue.Remote -> {
                if (lev.isOwn) return null
                val who = lev.sender.removePrefix("@").substringBefore(":")
                when (val c = lev.content) {
                    is TimelineItemContent.MsgLike -> {
                        val k = c.content.kind
                        if (k is MsgLikeKind.Message) Notif(id, name, name, k.content.body) else null
                    }
                    is TimelineItemContent.RtcNotification ->
                        Notif(id, name, "📞 Incoming call", "$who is calling — over Tor", isCall = true)
                    is TimelineItemContent.CallInvite ->
                        Notif(id, name, "📞 Incoming call", "$who is calling — over Tor", isCall = true)
                    else -> null
                }
            }
            is LatestEventValue.RemoteInvite -> {
                val who = (lev.inviter ?: "Someone").removePrefix("@").substringBefore(":")
                Notif(id, name, "New chat invite", "$who wants to connect over Tor", isInvite = true)
            }
            else -> null
        }
    }

    /** The name to show for a room. For a 1:1 DM that's the *other person*: the
     *  SDK's `heroes` are the non-self members the server summarised for the room,
     *  so for alice↔bob this resolves to "bob" on alice's phone and "alice" on
     *  bob's. Falls back to the room's own display name for non-DM / unsummarised
     *  rooms. Cross-box the peer's profile name often isn't replicated, so we use
     *  their matrix-id localpart (which is exactly their username). */
    /** The contact's full @name:onion for this room, from the heroes list (the non-me,
     *  non-bot member). Null when the SDK hasn't populated heroes yet — the chip just
     *  hides in that case. Used for the read-back identity chip on a pending row. */
    private fun peerId(r: Room): String? =
        runCatching { r.heroes() }.getOrDefault(emptyList())
            .firstOrNull { it.userId != userId && !isServerBot(it.userId) }
            ?.userId
            ?.takeIf { it.startsWith("@") && it.contains(":") }

    /** The DM's intended peer as recorded in `m.direct` ({"<userId>":["<roomId>",…]}),
     *  independent of who is actually a *member*. [peerId] reads `heroes()`, which is
     *  EMPTY for a room that only we ever joined — exactly the broken half-invite case
     *  (we created the DM and "invited" a peer whose box hadn't allowlisted us yet, so
     *  the invite PDU was 403'd and never committed; the room is left with only us in
     *  it). Without this the room can never be identified as a DM-with-@peer, so the
     *  self-heal re-invite [reInviteStalePeers] skips it forever and the pairing wedges.
     *  Returns the (first) userId whose `m.direct` room-list contains [roomId]. */
    private suspend fun directPeerOf(roomId: String): String? = runCatching {
        val raw = client?.accountData("m.direct") ?: return@runCatching null
        val obj = org.json.JSONObject(raw)
        for (uid in obj.keys()) {
            if (uid == userId || isServerBot(uid)) continue
            val arr = obj.optJSONArray(uid) ?: continue
            for (i in 0 until arr.length()) if (arr.getString(i) == roomId) {
                return@runCatching uid.takeIf { it.startsWith("@") && it.contains(":") }
            }
        }
        null
    }.getOrNull()

    private fun roomName(r: Room): String {
        val hero = runCatching { r.heroes() }.getOrDefault(emptyList())
            .firstOrNull { it.userId != userId && !isServerBot(it.userId) }
        if (hero != null) {
            val dn = hero.displayName?.trim().orEmpty()
            if (dn.isNotBlank() && !dn.startsWith("@") && !dn.contains(".onion")
                && !Regex("^[a-z2-7]{40,}").containsMatchIn(dn)) return dn
            val local = hero.userId.removePrefix("@").substringBefore(":")
            if (local.isNotBlank()) return local
        }
        return prettyName(runCatching { r.displayName() }.getOrNull(), r.id())
    }

    /** Turn whatever the SDK hands back into something a human wants to read.
     *  Unnamed/system rooms otherwise leak a room id or a raw onion string. */
    private fun prettyName(raw: String?, id: String): String {
        val n = raw?.trim().orEmpty()
        if (n.isBlank() || n.startsWith("!")) return "Encrypted chat"
        if (n.startsWith("@")) return n.removePrefix("@").substringBefore(":")
        // a bare onion / base32 blob is not a friendly name
        if (n.contains(".onion") || Regex("^[a-z2-7]{40,}").containsMatchIn(n)) return "Encrypted chat"
        return n
    }

    suspend fun openRoom(roomId: String) {
        val rls = roomListService ?: error("no room list")
        timelineItems.clear()
        sendHandles.clear()              // resend handles belong to the previous room's timeline
        messages.value = emptyList()
        val room = runCatching { rls.room(roomId) }.getOrNull()
            ?: roomHandles[roomId] ?: error("room not found")
        // NB: no auto-join here. Accepting a contact (joining the room) only happens
        // by scanning the other person's code — the mutual-consent rule. The chat
        // list routes a tap on an incoming request to the scanner instead of here.
        // Tear down the previous room's timeline subscription before opening another,
        // or the old TaskHandle leaks and stale diffs keep mutating timelineItems.
        runCatching { timelineHandle?.cancel() }
        timelineHandle = null
        runCatching { timeline?.destroy() }
        currentRoom = room
        currentRoomId = roomId
        val tl = room.timeline()
        timeline = tl
        timelineHandle = tl.addListener(object : TimelineListener {   // HOLD the handle
            override fun onUpdate(diff: List<TimelineDiff>) {
                applyTimelineDiffs(diff)
            }
        })
        runCatching { tl.paginateBackwards(50u.toUShort()) }
    }

    private fun applyTimelineDiffs(diffs: List<TimelineDiff>) {
        synchronized(timelineItems) {
            for (d in diffs) {
                when (d) {
                    is TimelineDiff.Append -> timelineItems.addAll(d.values)
                    is TimelineDiff.PushBack -> timelineItems.add(d.value)
                    is TimelineDiff.PushFront -> timelineItems.add(0, d.value)
                    is TimelineDiff.Insert -> timelineItems.add(d.index.toInt(), d.value)
                    is TimelineDiff.Set -> if (d.index.toInt() < timelineItems.size) timelineItems[d.index.toInt()] = d.value
                    is TimelineDiff.Remove -> if (d.index.toInt() < timelineItems.size) timelineItems.removeAt(d.index.toInt())
                    is TimelineDiff.Reset -> { timelineItems.clear(); timelineItems.addAll(d.values) }
                    is TimelineDiff.Clear -> timelineItems.clear()
                    is TimelineDiff.PopBack -> if (timelineItems.isNotEmpty()) timelineItems.removeAt(timelineItems.size - 1)
                    is TimelineDiff.PopFront -> if (timelineItems.isNotEmpty()) timelineItems.removeAt(0)
                    is TimelineDiff.Truncate -> while (timelineItems.size > d.length.toInt()) timelineItems.removeAt(timelineItems.size - 1)
                }
            }
            messages.value = timelineItems.mapNotNull { it.toChatMsg(userId) }
        }
    }

    private fun TimelineItem.toChatMsg(me: String): ChatMsg? {
        val ev = runCatching { this.asEvent() }.getOrNull() ?: return null
        val content = ev.content
        if (content is TimelineItemContent.MsgLike) {
            val kind = content.content.kind
            if (kind is MsgLikeKind.Message) {
                val mc = kind.content
                val sender = ev.sender
                val key = runCatching { ev.eventOrTransactionId.toString() }
                    .getOrDefault("$sender:${mc.body}:${System.identityHashCode(this)}")
                val ts = runCatching { ev.timestamp.toLong() }.getOrDefault(0L)
                // Attachments render as a tappable chip carrying the media source.
                var text = mc.body; var media: org.matrix.rustcomponents.sdk.MediaSource? = null
                var fileName: String? = null; var mime: String? = null; var isImage = false
                when (val mt = mc.msgType) {
                    is org.matrix.rustcomponents.sdk.MessageType.File ->
                        { media = mt.content.source; fileName = mt.content.filename ?: mc.body; mime = mt.content.info?.mimetype; text = "📎 $fileName" }
                    is org.matrix.rustcomponents.sdk.MessageType.Image ->
                        { media = mt.content.source; fileName = mt.content.filename ?: mc.body; mime = "image/*"; isImage = true; text = "🖼️ $fileName" }
                    is org.matrix.rustcomponents.sdk.MessageType.Video ->
                        { media = mt.content.source; fileName = mc.body; text = "🎞️ ${mc.body}" }
                    is org.matrix.rustcomponents.sdk.MessageType.Audio ->
                        { media = mt.content.source; fileName = mc.body; text = "🎵 ${mc.body}" }
                    else -> {}
                }
                val mine = sender == me
                // Delivery state of our OWN message, straight from the SDK local echo.
                // localSendState is non-null only while a message is in the send queue
                // (NotSentYet/SendingFailed) — once it round-trips and the server echoes
                // it back it's a plain remote item (null state) → Sent. Remote (others')
                // messages are always Sent. We never hand-roll a timer; this reflects the
                // SDK's real queue state.
                val sendState = if (mine) sendStateOf(ev, key) else SendState.Sent
                return ChatMsg(key, sender, text, mine, ts, media, fileName, mime, isImage, sendState)
            }
            // An event we received but can't decrypt (missing the megolm room key —
            // e.g. the sender used a new device whose key never reached us). Surface a
            // placeholder instead of silently dropping it, and log the session id so we
            // can tell "didn't arrive" apart from "arrived but undecryptable".
            if (kind is MsgLikeKind.UnableToDecrypt) {
                val sender = ev.sender
                val ts = runCatching { ev.timestamp.toLong() }.getOrDefault(0L)
                val sid = (kind.msg as? EncryptedMessage.MegolmV1AesSha2)?.sessionId
                Log.w(TAG, "UTD event from $sender ts=$ts session=$sid")
                val key = runCatching { ev.eventOrTransactionId.toString() }.getOrDefault("utd:$sender:$ts")
                return ChatMsg(key, sender, "🔒 Can't decrypt this message", sender == me, ts)
            }
        }
        return null
    }

    /** Map the SDK's local-echo send state onto our [SendState], and stash the
     *  message's resend handle so a failed bubble can be retried. The handle (off the
     *  item's lazy provider) is the SDK's own resend path — no hand-rolled retry. A
     *  Sent/round-tripped item drops its handle. Failures here default to Sending so a
     *  transient read never paints a healthy message as failed. */
    private fun sendStateOf(ev: org.matrix.rustcomponents.sdk.EventTimelineItem, key: String): SendState {
        return when (ev.localSendState) {
            is EventSendState.SendingFailed -> {
                runCatching { ev.lazyProvider.getSendHandle() }.getOrNull()?.let { sendHandles[key] = it }
                SendState.Failed
            }
            is EventSendState.NotSentYet -> {
                runCatching { ev.lazyProvider.getSendHandle() }.getOrNull()?.let { sendHandles[key] = it }
                SendState.Sending
            }
            // EventSendState.Sent, or null (a plain remote item that round-tripped) —
            // delivered. Drop any resend handle we were holding for it.
            else -> { sendHandles.remove(key); SendState.Sent }
        }
    }

    /** Retry a failed own-message via the SDK's resend path (SendHandle.tryResend()).
     *  The timeline will re-emit the item as NotSentYet → Sent (or Failed again),
     *  which flows back through toChatMsg to the bubble. No-op if we hold no handle. */
    suspend fun retrySend(key: String) {
        val h = sendHandles[key] ?: return
        runCatching { h.tryResend() }
            .onSuccess { Log.i(TAG, "retrySend: re-queued $key") }
            .onFailure { Log.w(TAG, "retrySend failed for $key: ${it.message}") }
    }

    /** [H4] Is this room CONFIRMED to be unencrypted? Conservative on purpose: we only
     *  return true when the SDK affirmatively reports the room is NOT encrypted. We use
     *  latestEncryptionState() — a 3-state enum (ENCRYPTED / NOT_ENCRYPTED / UNKNOWN) —
     *  and block ONLY on NOT_ENCRYPTED. UNKNOWN (state not yet synced over Tor, e.g. an
     *  invite whose stripped m.room.encryption hasn't warmed) and any read error fall
     *  through to false so we NEVER block a legitimate encrypted room — our normal DMs
     *  are all created with isEncrypted=true. NB: room.isEncrypted() collapses UNKNOWN
     *  to false, which would mis-classify an un-warmed encrypted invite as unencrypted
     *  and transiently skip its auto-join; latestEncryptionState() avoids that. The
     *  point is purely to refuse sending cleartext into a room that genuinely has no
     *  encryption (e.g. a malformed/injected room), never silently. */
    private suspend fun isConfirmedUnencrypted(room: Room): Boolean {
        val state = runCatching { room.latestEncryptionState() }.getOrNull() ?: return false
        return state == uniffi.matrix_sdk_base.EncryptionState.NOT_ENCRYPTED
    }

    suspend fun send(text: String) {
        val tl = timeline ?: return
        // [H4] Never send cleartext: if the open room is confirmed unencrypted, refuse
        // and surface a clear error rather than leaking the message in the clear. A
        // no-op for our normal E2EE DMs (latestEncryptionState() == ENCRYPTED).
        currentRoom?.let { room ->
            if (isConfirmedUnencrypted(room)) {
                Log.w(TAG, "refusing to send into a non-encrypted room ${runCatching { room.id() }.getOrNull()}")
                status.value = "Not sent — this chat isn't encrypted."
                return
            }
        }
        tl.send(messageEventContentFromMarkdown(text))
    }

    /** Send a file/attachment from a content URI — uploaded E2EE by the SDK and
     *  federated over Tor. Copies the picked content to a cache file first because
     *  the SDK's UploadSource needs a real path. */
    suspend fun sendFile(ctx: Context, uri: android.net.Uri) {
        val tl = timeline ?: return
        val cr = ctx.contentResolver
        // Keep only the basename — the SDK uses the file's basename as the displayed
        // filename, so stash the copy in a unique subdir under that clean name.
        val name = (queryDisplayName(cr, uri) ?: "file").substringAfterLast('/').ifBlank { "file" }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val dir = File(ctx.cacheDir, "pp_up/${System.currentTimeMillis()}").apply { mkdirs() }
        val tmp = File(dir, name)
        runCatching { cr.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } }
        if (!tmp.exists() || tmp.length() == 0L) { runCatching { dir.deleteRecursively() }; return }
        // UploadParameters(source, caption, formattedCaption, mentions, inReplyTo) —
        // the trailing arg is a reply event-id, NOT the filename. Pass null or the SDK
        // throws InvalidRepliedToEventId. The filename rides on UploadSource.File's path.
        val params = org.matrix.rustcomponents.sdk.UploadParameters(
            org.matrix.rustcomponents.sdk.UploadSource.File(tmp.absolutePath), null, null, null, null
        )
        // Send everything as a generic file (reliable). NB: sending as m.image via
        // Timeline.sendImage currently throws RoomException.InvalidAttachmentData on
        // sdk-android 26.06.11 — to revisit. Incoming m.image events still render
        // inline (see toChatMsg/Bubble), e.g. from an Element client.
        val info = org.matrix.rustcomponents.sdk.FileInfo(mime, tmp.length().toULong(), null, null)
        runCatching { tl.sendFile(params, info).join() }.onFailure { Log.e(TAG, "sendFile failed", it) }
        runCatching { dir.deleteRecursively() }
    }

    private val mediaCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Fetch an attachment's bytes over Tor, cached by message key — used to render
     *  inline image thumbnails without re-downloading on every recomposition. */
    suspend fun mediaBytes(key: String, media: org.matrix.rustcomponents.sdk.MediaSource): ByteArray? {
        mediaCache[key]?.let { return it }
        val c = client ?: return null
        val bytes = runCatching { c.getMediaContent(media) }.getOrNull() ?: return null
        if (mediaCache.size > 60) mediaCache.clear()   // crude bound; fine at chat scale
        mediaCache[key] = bytes
        return bytes
    }

    /** Download an attachment over Tor and save it to the device's Downloads. */
    suspend fun saveAttachment(ctx: Context, media: org.matrix.rustcomponents.sdk.MediaSource, name: String, mime: String?): Boolean {
        val c = client ?: return false
        val bytes = runCatching { c.getMediaContent(media) }.getOrNull() ?: return false
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val v = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                }
                val u = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    ?: return false
                ctx.contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
            } else {
                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                File(dir, name).outputStream().use { it.write(bytes) }
            }
            true
        }.getOrDefault(false)
    }

    private fun queryDisplayName(cr: android.content.ContentResolver, uri: android.net.Uri): String? =
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst() && cur.columnCount > 0) cur.getString(0) else null
            }
        }.getOrNull()

    /** Load our recorded consent (scanned-contact onions) from account data into
     *  memory, so consent survives an app restart. Account-data is the single source
     *  of truth: REPLACE the in-memory set (clear + fill) rather than union/add-only,
     *  so a removal on this or another device propagates here and a removed peer can't
     *  be resurrected. Re-run on every RUNNING sync cycle. Best-effort — on a failed
     *  read we keep the existing set (a transient Tor read must never drop consent). */
    private suspend fun loadConsent() {
        client?.let { consent.load(it) }
    }

    /** Record consent for a scanned contact: remember their box's onion (the server
     *  part of their @id) in account data. Our box's desktop app reads this and
     *  allowlists the onion for federation — so scanning their QR pairs the boxes. */
    private suspend fun recordPairing(peerUserId: String) {
        val c = client ?: return
        val onion = peerUserId.substringAfter(":", "").trim()
        if (!onion.endsWith(".onion")) { Log.w(TAG, "recordPairing: '$onion' is not an onion"); return }
        consent.record(c, onion)
    }

    /** Inverse of [recordPairing]: drop a contact's onion from account-data (the
     *  authoritative pairing set), so our box's reconcile cuts it from the federation
     *  allowlist. Reads the current `…pairings` array, removes this onion, and writes
     *  it back with the SAME 8× retry over flaky Tor. Drops the onion from the
     *  in-memory consent set ONLY after a successful PUT — never leave account-data and
     *  consent out of sync (a failed write must NOT silently revoke consent locally;
     *  the chat must stay live and re-runnable). Returns true once it actually
     *  persisted. */
    private suspend fun removeOnionFromAccountData(onion: String): Boolean {
        val c = client ?: return false
        return consent.remove(c, onion)
    }

    /** Remove a contact (@user:onion). Destructive by default — drops the peer's onion
     *  from account-data so our box's reconcile cuts them from the federation allowlist
     *  (their box can no longer reach ours). When [notify] is true we additionally
     *  leave + forget the DM room(s), federating a "left" event so the still-paired peer
     *  sees we're gone; when false the removal is silent (no leave event — they simply
     *  become unreachable, not invisible).
     *
     *  Strict no-half-remove ordering: we ALWAYS persist the account-data drop (the real
     *  federation cut) FIRST, and only on a confirmed write do we federate the "left"
     *  event (notify) and drop in-memory consent. If the account-data write fails we
     *  throw — consent stays intact, no "left" event fires, the chat stays live, and the
     *  whole operation is fully re-runnable. This guarantees the federated "left" never
     *  outraces the cut: the peer is never told we left while their box can still reach
     *  ours. */
    suspend fun removeContact(peer: String, notify: Boolean) {
        val onion = peer.substringAfter(":").trim()
        // Resolve the DM room(s) ONCE — one SDK/Tor round trip reused below.
        val dms = runCatching { client?.getDmRooms(peer) }.getOrNull().orEmpty()
        // Tear down any in-progress call in the DM room first, so a removed-but-hidden
        // room can't keep ringing or leave a ghost call membership behind.
        dms.forEach { r ->
            val hasCall = runCatching { r.hasActiveRoomCall() }.getOrDefault(false)
            if (hasCall) clearMyCallMembership(r.id())
        }
        // The real cut: drop the onion from account-data BEFORE anything user-visible
        // federates. On failure (8× retry exhausted over flaky Tor) consent is left
        // intact by removeOnionFromAccountData; throw so the UI surfaces a retry state
        // and never reports a successful removal for a half-completed cut.
        val cut = removeOnionFromAccountData(onion)
        if (!cut) {
            rebuildRooms()
            throw java.io.IOException(
                "Couldn't reach your box to cut them off — still connected; try again."
            )
        }
        if (notify) {
            // Resolve the ids BEFORE leave()/forget() touch the handles, so the prune's
            // id set can't be perturbed by any leave/forget side effect. Room.id() is the
            // handle's immutable id, but hoisting keeps the prune all-or-nothing-proof.
            val leftIds = dms.map { it.id() }.toSet()
            // Only now that the cut is persisted do we federate the "left" event the peer
            // sees. leave() then forget() — order matters: leaving federates the "left"
            // event; forgetting drops the room from our list.
            dms.forEach { r ->
                runCatching { r.leave() }
                runCatching { r.forget() }
            }
            // Best-effort cosmetic cleanup: prune the rooms we just left from the
            // `m.direct` account-data ({"<userId>":["<roomId>",...]}). A left+forgotten
            // room can linger there and resurface as a ghost/dead DM from getDmRooms on a
            // later re-add. Wrapped in runCatching so a prune failure NEVER fails the
            // removal — the real cut already persisted above. Idempotent: re-running just
            // finds nothing to remove.
            runCatching {
                val c = client ?: return@runCatching
                val raw = c.accountData("m.direct") ?: return@runCatching
                val obj = org.json.JSONObject(raw)
                var changed = false
                val keep = org.json.JSONObject()
                for (uid in obj.keys()) {
                    val arr = obj.optJSONArray(uid) ?: continue
                    val kept = org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val roomId = arr.getString(i)
                        if (roomId in leftIds) changed = true else kept.put(roomId)
                    }
                    // Drop keys whose list became empty; keep the rest.
                    if (kept.length() > 0) keep.put(uid, kept) else if (arr.length() > 0) changed = true
                }
                if (changed) c.setAccountData("m.direct", keep.toString())
            }
        }
        rebuildRooms()
    }

    /** Auto-accept incoming invites from contacts we've ALSO scanned: mutual consent
     *  is satisfied, so the chat goes live without any extra tap. An invite from
     *  someone we haven't scanned is left pending (shown as "scan their code"). */
    private suspend fun autoAcceptMutual() {
        val c = client ?: return
        val handles = synchronized(roomHandles) { roomHandles.values.toList() }
        for (r in handles) {
            val invited = runCatching { r.membership() == Membership.INVITED }.getOrDefault(false)
            if (!invited) continue
            val inviter = runCatching { r.inviter() }.getOrNull()?.userId ?: continue
            val onion = inviter.substringAfter(":", "")
            if (onion in consent) {
                // [H4] Don't auto-join a room we can CONFIRM is unencrypted — refuse and
                // log rather than silently entering a cleartext room from an invite.
                // isConfirmedUnencrypted is false on unknown/unresolved state, so a
                // genuinely-encrypted invite (state not yet warm) still auto-joins as
                // before: this is a no-op for our normal E2EE DMs.
                if (isConfirmedUnencrypted(r)) {
                    Log.w(TAG, "refusing auto-join of non-encrypted room ${r.id()} from $inviter")
                    continue
                }
                runCatching { c.joinRoomById(r.id()) }
            }
        }
    }

    /** Re-drive outgoing invites that haven't landed yet. `startChat`'s one-shot
     *  `ensureInvited` gives up after ~2.4 min, but a fresh peer's box can take longer than
     *  that to become reachable over Tor — so re-invite every sync cycle, unbounded, until
     *  the peer is a member. Idempotent (skips peers already Join/Invite, so no spam) and —
     *  unlike the fire-once launch — this survives an app restart. Only for peers whose onion
     *  we've consented to; never touches the @conduit admin room. This is the receiver-agnostic
     *  half of "pairing eventually converges even over a slow/flaky first-contact circuit." */
    private suspend fun reInviteStalePeers() {
        val c = client ?: return
        val handles = synchronized(roomHandles) { roomHandles.values.toList() }
        for (r in handles) {
            val joined = runCatching { r.membership() == Membership.JOINED }.getOrDefault(false)
            if (!joined) continue
            if (peerJoined(r)) continue          // both in → live already
            // Resolve the intended peer. peerId (heroes) is EMPTY for a creator-only
            // broken-invite room, so fall back to m.direct — which still records this as
            // a DM-with-@peer even when the peer never became a member. If NEITHER
            // resolves a peer, it's a genuinely peerless room (e.g. the @conduit admin
            // room) → skip. This replaces the old `lacksHumanPeer` guard, which skipped
            // creator-only rooms and left broken invites permanently wedged.
            val peer = peerId(r) ?: directPeerOf(r.id()) ?: continue
            if (peer.substringAfter(":", "") !in consent) continue
            // Skip if the peer is already a member (Join or Invite) — the invite landed and
            // we're just waiting on their auto-accept; re-issuing would be needless churn.
            val present = runCatching {
                when (r.member(peer).membership) {
                    is MembershipState.Join, is MembershipState.Invite -> true
                    else -> false
                }
            }.getOrDefault(false)
            if (present) continue
            val res = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(20_000) { r.inviteUserById(peer) }
            }
            if (res.isFailure) Log.w(TAG, "reInvite: $peer -> ${r.id()} failed: ${res.exceptionOrNull()?.message}")
            else Log.i(TAG, "reInvite: (re)sent invite to $peer for ${r.id()}")
        }
    }

    /** Find a room we've been INVITED to whose inviter is [peer] — a DM the peer
     *  created and is waiting for us to join. Lets a mutual QR-scan converge on the
     *  peer's room instead of spawning a competing one. */
    private suspend fun findInviteFrom(peer: String): String? {
        val handles = synchronized(roomHandles) { roomHandles.values.toList() }
        for (r in handles) {
            val invited = runCatching { r.membership() == Membership.INVITED }.getOrDefault(false)
            if (!invited) continue
            val inviter = runCatching { r.inviter() }.getOrNull()?.userId
            if (inviter == peer) return r.id()
        }
        return null
    }

    /** Act on a scanned/typed contact (@user:onion). Mutual consent: a conversation
     *  only goes live once BOTH people have scanned each other. Scanning does two
     *  things — records consent (so our box allowlists their onion for federation,
     *  and we'll auto-accept their invite) and, for one side, sends the invite.
     *
     *  Returns [ConnectResult] — `paired=true` means it's live now (they'd already
     *  scanned us), `paired=false` means pending until they scan back. Exactly ONE
     *  room is ever created, with no race: the lexicographically smaller user id is
     *  the sole creator; the larger never creates — it just waits and auto-joins the
     *  invite once it federates over Tor (see [autoAcceptMutual]). Federates over Tor. */
    suspend fun startChat(userId: String): ConnectResult {
        val c = client ?: error("not logged in")
        val me = this.userId
        // Record consent in the background: our box's desktop reads this account data
        // and allowlists their onion (the box-pairing half of a QR exchange). It can
        // retry over flaky Tor without blocking the UI; once it lands we re-run the
        // mutual-accept pass in case their invite is already waiting.
        scope.launch { recordPairing(userId); autoAcceptMutual(); rebuildRooms() }
        // 1. Already share a LIVE DM (both joined)? reuse it. (A room the peer has
        //    left doesn't count — peerJoined excludes the bot and left members.)
        val dms = runCatching { c.getDmRooms(userId) }.getOrDefault(emptyList())
        dms.firstOrNull { peerJoined(it) }?.let { return ConnectResult(it.id(), true) }
        // 2. The peer already invited us → join → both in → live (mutual).
        findInviteFrom(userId)?.let { rid ->
            runCatching { c.joinRoomById(rid) }
            return ConnectResult(rid, true)
        }
        // 3. No invite yet. The smaller id creates + invites; the larger id creates
        //    nothing (avoids a duplicate room) and waits — when the smaller's invite
        //    federates over Tor, autoAcceptMutual joins it (we've consented). Either
        //    way the chat goes live only once both have scanned.
        if (me < userId) {
            // Reuse a DM we already created for this peer if one exists (so a re-scan
            // doesn't spawn another room), else make one. Either way (re)invite the
            // peer separately with retry: a remote invite only sticks once their box
            // has allowlisted us (a few seconds after they scan us), so createRoom's
            // one-shot invite would fail silently and never recover.
            // Let EITHER party start a call: the RTC membership state events
            // (org.matrix.msc3401.call.member) default to needing PL50, so the invited
            // peer (PL0) gets 403 "not enough power" and the call dies with "Connection
            // lost". Drop those event types to PL0 so any room member can join a call.
            val callPowerLevels = org.matrix.rustcomponents.sdk.PowerLevels(
                usersDefault = null, eventsDefault = null, stateDefault = null,
                ban = null, kick = null, redact = null, invite = null, notifications = null,
                users = emptyMap(),
                events = mapOf(
                    "org.matrix.msc3401.call.member" to 0,
                    "m.call.member" to 0,
                    "org.matrix.msc3401.call" to 0,
                    "m.call" to 0,
                ),
            )
            val rid = dms.firstOrNull {
                runCatching { it.membership() == Membership.JOINED }.getOrDefault(false)
            }?.id() ?: c.createRoom(
                CreateRoomParameters(
                    name = null,
                    topic = null,
                    isEncrypted = true,          // E2EE on — the SDK manages keys
                    isDirect = true,
                    visibility = RoomVisibility.Private,
                    preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
                    invite = emptyList(),
                    avatar = null,
                    powerLevelContentOverride = callPowerLevels,
                    joinRuleOverride = null,
                    historyVisibilityOverride = null,
                    canonicalAlias = null,
                    isSpace = false,
                )
            )
            scope.launch { ensureInvited(rid, userId) }
            return ConnectResult(rid, false)
        }
        // larger id: nothing to create; their invite will arrive and auto-join.
        return ConnectResult("", false)
    }

    /** (Re)invite [peer] to [roomId] until the invite sticks. Federated invites only
     *  succeed once the peer's box has allowlisted us — which happens a few seconds
     *  after they scan us — so retry over Tor instead of failing once and giving up. */
    private suspend fun ensureInvited(roomId: String, peer: String) {
        val c = client ?: return
        for (attempt in 1..24) {
            val room = runCatching { c.getRoom(roomId) }.getOrNull()
            if (room != null) {
                val present = runCatching {
                    when (room.member(peer).membership) {
                        is MembershipState.Join, is MembershipState.Invite -> true
                        else -> false
                    }
                }.getOrDefault(false)
                if (present) { Log.i(TAG, "ensureInvited: $peer present in $roomId (attempt $attempt)"); return }
                // Bound the invite: it blocks on our box's SYNCHRONOUS federated invite, which
                // over a cold Tor circuit can hang far past the 6s cadence. Time it out so the
                // retry loop keeps its rhythm.
                val r = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(20_000) { room.inviteUserById(peer) }
                }
                if (r.isFailure) Log.w(TAG, "ensureInvited attempt $attempt: ${r.exceptionOrNull()?.message}")
            }
            kotlinx.coroutines.delay(6000)
        }
        // Not a dead end: the per-sync-cycle reInviteStalePeers keeps re-inviting unbounded
        // until the peer's box becomes reachable over Tor (and survives an app restart).
        Log.i(TAG, "ensureInvited: initial burst done for $peer; sync reconcile will keep re-inviting")
    }

    /** Sign out: best-effort server logout, then wipe the local session so the
     *  next launch lands on Login. */
    suspend fun logout(ctx: Context) {
        runCatching { syncService?.stop() }
        runCatching { client?.logout() }
        runCatching { sessionPrefs(ctx).edit().clear().apply() }
        runCatching { ctx.getSharedPreferences(SESSION_OLD, Context.MODE_PRIVATE).edit().clear().apply() }
        runCatching { timelineHandle?.cancel() }
        runCatching { syncStateHandle?.cancel() }; syncStateHandle = null; syncRestartDelayMs = 0L
        runCatching { clientDelegateHandle?.cancel() }; clientDelegateHandle = null   // [H1]
        syncHardFailures = 0; authExpired.value = false                               // [H1]
        runCatching { callSubs.values.forEach { it.cancel() } }; callSubs.clear()
        timelineHandle = null; roomListResult = null; roomList = null
        client = null; syncService = null; roomListService = null; timeline = null
        currentRoom = null; currentRoomId = null; userId = ""; deviceId = ""
        roomHandles.clear(); timelineItems.clear(); sendHandles.clear()
        lastSeen.clear(); notifyArmed = false; activeCallRooms.clear()
        rooms.value = emptyList(); messages.value = emptyList()
        wipeStore(ctx)                                  // remove crypto store too
        status.value = ""
    }
}
