package ai.tournesol.pureprivacy.matrix

import android.content.Context
import android.util.Log
import ai.tournesol.pureprivacy.tor.TorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
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

data class RoomSummary(val id: String, val name: String)
data class ChatMsg(val key: String, val sender: String, val body: String, val mine: Boolean)

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

    val rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    val messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val status = MutableStateFlow("")

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

    suspend fun login(ctx: Context, homeserverUrl: String, user: String, pass: String) {
        status.value = "Connecting over Tor…"
        val c = builder(ctx, homeserverUrl).build()
        client = c
        status.value = "Signing in…"
        c.login(user, pass, "PurePrivacy Android", null)
        userId = runCatching { c.userId() }.getOrDefault("@$user")
        runCatching { persist(ctx, c.session()) }
        Log.i(TAG, "logged in as $userId")
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
        Log.i(TAG, "restored session for $userId")
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
                val name = runCatching { r.displayName() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: r.id()
                RoomSummary(r.id(), name)
            }
        }
    }

    suspend fun openRoom(roomId: String) {
        val rls = roomListService ?: error("no room list")
        timelineItems.clear()
        messages.value = emptyList()
        val room = runCatching { rls.room(roomId) }.getOrNull()
            ?: roomHandles[roomId] ?: error("room not found")
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
}
