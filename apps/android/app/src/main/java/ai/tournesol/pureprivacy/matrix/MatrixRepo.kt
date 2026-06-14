package ai.tournesol.pureprivacy.matrix

import android.content.Context
import android.util.Log
import ai.tournesol.pureprivacy.tor.TorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListService
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

data class RoomSummary(val id: String, val name: String, val invited: Boolean = false)
data class ChatMsg(val key: String, val sender: String, val body: String, val mine: Boolean)
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

    private var client: Client? = null
    private var syncService: SyncService? = null
    private var roomListService: RoomListService? = null
    private var timeline: Timeline? = null

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

    /** Background-notification stream — the foreground service collects this and
     *  posts Android notifications for messages / calls / invites. */
    val notifications = MutableSharedFlow<Notif>(extraBufferCapacity = 32)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var notifyArmed = false   // don't fire for the initial backfill
    @Volatile private var notifyFromMs = 0L     // only notify for events newer than this
    @Volatile private var pollStarted = false

    private val roomHandles = LinkedHashMap<String, Room>()
    private val timelineItems = ArrayList<TimelineItem>()

    val isLoggedIn: Boolean get() = client != null

    private fun builder(ctx: Context, homeserverUrl: String?): ClientBuilder {
        val base = File(ctx.filesDir, "matrix").apply { mkdirs() }
        val session = File(base, "session").apply { mkdirs() }
        val cache = File(base, "cache").apply { mkdirs() }
        var b = ClientBuilder()
            .sessionPaths(session.absolutePath, cache.absolutePath)
            .proxy(TorManager.proxyUrl)                 // route every request through Tor
            .disableSslVerification()                   // onion box uses a self-signed/plain endpoint
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
            .autoEnableCrossSigning(false)
            .autoEnableBackups(false)
        if (homeserverUrl != null) b = b.homeserverUrl(homeserverUrl)
        return b
    }

    /** Delete the on-disk Matrix session + crypto store. A stale store from a
     *  previous account/device causes the SDK's MismatchedAccount crypto error on a
     *  fresh login, so we always start a sign-in from clean state. */
    private fun wipeStore(ctx: Context) {
        runCatching { File(ctx.filesDir, "matrix").deleteRecursively() }
    }

    suspend fun login(ctx: Context, homeserverUrl: String, user: String, pass: String) {
        status.value = "Connecting over Tor…"
        wipeStore(ctx)                                  // clean crypto store for this sign-in
        val c = builder(ctx, homeserverUrl).build()
        client = c
        status.value = "Signing in…"
        c.login(user, pass, "PurePrivacy Android", null)
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
        val p = ctx.getSharedPreferences("pp_session", Context.MODE_PRIVATE)
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
        userId = s.userId
        deviceId = s.deviceId
        Log.i(TAG, "restored session for $userId (device $deviceId)")
        return true
    }

    private fun persist(ctx: Context, s: Session) {
        ctx.getSharedPreferences("pp_session", Context.MODE_PRIVATE).edit()
            .putString("at", s.accessToken)
            .putString("rt", s.refreshToken)
            .putString("uid", s.userId)
            .putString("did", s.deviceId)
            .putString("hs", s.homeserverUrl)
            .putString("oauth", s.oauthData)
            .putString("ssv", s.slidingSyncVersion.name)
            .apply()
    }

    suspend fun startSync() {
        val c = client ?: error("not logged in")
        val ss = c.syncService().finish()
        syncService = ss
        ss.start()
        val rls = ss.roomListService()
        roomListService = rls
        val roomList = rls.allRooms()
        val result = roomList.entriesWithDynamicAdapters(
            200u,
            object : RoomListEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                    applyRoomUpdates(roomEntriesUpdate)
                }
            }
        )
        // populate: no extra filtering, just show every room we're in
        result.controller().setFilter(RoomListEntriesDynamicFilterKind.All(emptyList()))
        status.value = "Connected"
        // arm notifications: only events from ~now on are "new" (avoids backfill).
        notifyFromMs = System.currentTimeMillis()
        scope.launch { kotlinx.coroutines.delay(3000); notifyArmed = true }
        // The room-list listener only fires on list *reordering* — a new message to
        // the already-top room doesn't reorder it. So poll latest events too; this is
        // a cheap local read (sliding sync has already cached the data).
        if (!pollStarted) {
            pollStarted = true
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(2500)
                    runCatching { checkNotifs() }
                }
            }
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
            rooms.value = roomHandles.values.map { r ->
                val raw = runCatching { r.displayName() }.getOrNull()
                val invited = runCatching { r.membership() == Membership.INVITED }.getOrDefault(false)
                RoomSummary(r.id(), prettyName(raw, r.id()), invited)
            }
        }
        scope.launch { checkNotifs() }
    }

    /** Look at each room's latest event and emit a notification for anything new
     *  the user isn't already looking at (a message, an incoming call, an invite). */
    private suspend fun checkNotifs() {
        val handles = synchronized(roomHandles) { LinkedHashMap(roomHandles) }
        for ((id, room) in handles) {
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
        val name = prettyName(runCatching { room.displayName() }.getOrNull(), id)
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
        messages.value = emptyList()
        val room = runCatching { rls.room(roomId) }.getOrNull()
            ?: roomHandles[roomId] ?: error("room not found")
        // Accept an invite transparently: a contact who scanned your code invited
        // you — tapping the chat should just join it (over Tor), no extra step.
        if (runCatching { room.membership() == Membership.INVITED }.getOrDefault(false)) {
            status.value = "Joining over Tor…"
            runCatching { room.join() }
        }
        currentRoom = room
        currentRoomId = roomId
        val tl = room.timeline()
        timeline = tl
        tl.addListener(object : TimelineListener {
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
                val body = kind.content.body
                val sender = ev.sender
                val key = runCatching { ev.eventOrTransactionId.toString() }
                    .getOrDefault("$sender:$body:${System.identityHashCode(this)}")
                return ChatMsg(key, sender, body, sender == me)
            }
        }
        return null
    }

    suspend fun send(text: String) {
        val tl = timeline ?: return
        tl.send(messageEventContentFromMarkdown(text))
    }

    /** Start a direct, end-to-end-encrypted chat with another user (@user:onion),
     *  inviting them. Returns the room id. Reuses an existing DM with that user if
     *  one already exists (no duplicate rooms from scanning twice). Federates the
     *  invite over Tor. */
    suspend fun startChat(userId: String): String {
        val c = client ?: error("not logged in")
        // dedup: if we already share a DM with this person, open it.
        runCatching { c.getDmRoom(userId) }.getOrNull()?.let { return it.id() }
        val params = CreateRoomParameters(
            name = null,
            topic = null,
            isEncrypted = true,          // E2EE on — the SDK manages keys
            isDirect = true,
            visibility = RoomVisibility.Private,
            preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
            invite = listOf(userId),
            avatar = null,
            powerLevelContentOverride = null,
            joinRuleOverride = null,
            historyVisibilityOverride = null,
            canonicalAlias = null,
            isSpace = false,
        )
        return c.createRoom(params)
    }

    /** Sign out: best-effort server logout, then wipe the local session so the
     *  next launch lands on Login. */
    suspend fun logout(ctx: Context) {
        runCatching { syncService?.stop() }
        runCatching { client?.logout() }
        ctx.getSharedPreferences("pp_session", Context.MODE_PRIVATE).edit().clear().apply()
        client = null; syncService = null; roomListService = null; timeline = null
        currentRoom = null; currentRoomId = null; userId = ""; deviceId = ""
        roomHandles.clear(); timelineItems.clear()
        lastSeen.clear(); notifyArmed = false
        rooms.value = emptyList(); messages.value = emptyList()
        wipeStore(ctx)                                  // remove crypto store too
        status.value = ""
    }
}
