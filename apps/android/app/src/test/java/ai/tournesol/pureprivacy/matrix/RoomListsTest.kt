package ai.tournesol.pureprivacy.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the room classification/dedup/partition rules in [RoomLists].
 *
 * Both regressions pinned here shipped silently in one week before this file existed:
 * the dedup collapsing an agent's conversations, and a fresh session room rendering in
 * Messaging as a stranger. Every test that mentions one of them says so.
 */
class RoomListsTest {

    private fun room(
        id: String,
        peerId: String? = null,
        paired: Boolean = false,
        isAgent: Boolean = false,
        invited: Boolean = false,
        outgoing: Boolean = false,
        ts: Long = 0L,
    ) = RoomSummary(
        id = id, name = id,
        invited = invited, paired = paired, outgoing = outgoing,
        isAgent = isAgent, peerId = peerId, ts = ts,
    )

    private fun agent(user: String, roomId: String = "") =
        MatrixRepo.AgentInfo(userId = user, displayName = user, roomId = roomId)

    // ---------------------------------------------------------------- agentFor ----

    @Test
    fun `session list recognises a conversation the agent has not joined yet`() {
        // THE regression: a just-created conversation has no hero and isn't the registry
        // room, so before the session-list rung it classified as a chat with a stranger.
        val chris = agent("@chris:onion", roomId = "!main")
        val got = RoomLists.agentFor(
            roomId = "!fresh", peerId = null,
            sessionAgentIds = mapOf("!fresh" to "@chris:onion"),
            agents = mapOf("@chris:onion" to chris),
        )
        assertEquals(chris, got)
    }

    @Test
    fun `registry room id recognises a provisioned agent before it joins its main room`() {
        val chris = agent("@chris:onion", roomId = "!main")
        val got = RoomLists.agentFor("!main", peerId = null, sessionAgentIds = emptyMap(),
            agents = mapOf("@chris:onion" to chris))
        assertEquals(chris, got)
    }

    @Test
    fun `a joined agent matches by counterpart id`() {
        val chris = agent("@chris:onion", roomId = "!main")
        val got = RoomLists.agentFor("!other", peerId = "@chris:onion",
            sessionAgentIds = emptyMap(), agents = mapOf("@chris:onion" to chris))
        assertEquals(chris, got)
    }

    @Test
    fun `a human room resolves to no agent`() {
        val chris = agent("@chris:onion", roomId = "!main")
        assertNull(RoomLists.agentFor("!dm", peerId = "@friend:onion",
            sessionAgentIds = emptyMap(), agents = mapOf("@chris:onion" to chris)))
    }

    @Test
    fun `a blank registry room id never matches every blank-id room`() {
        // AgentInfo.roomId defaults to "" — matching "" == "" would classify arbitrary
        // rooms as agent rooms. The isNotBlank guard is load-bearing.
        val chris = agent("@chris:onion", roomId = "")
        assertNull(RoomLists.agentFor("", peerId = null, sessionAgentIds = emptyMap(),
            agents = mapOf("@chris:onion" to chris)))
    }

    // --------------------------------------------------------------- partition ----

    @Test
    fun `duplicate human DMs collapse to the lowest room id`() {
        // Both sides sort the same, so both converge on one room and send there.
        val split = RoomLists.partition(listOf(
            room("!b", peerId = "@friend:onion", paired = true, ts = 2),
            room("!a", peerId = "@friend:onion", paired = true, ts = 1),
        ))
        assertEquals(listOf("!a"), split.chats.map { it.id })
    }

    @Test
    fun `an agent's several conversations are all kept`() {
        // THE regression: agents ran through the human dedup and every conversation but
        // the oldest vanished — looking exactly like the box losing them.
        val split = RoomLists.partition(listOf(
            room("!c1", peerId = "@chris:onion", paired = true, isAgent = true, ts = 3),
            room("!c2", peerId = "@chris:onion", paired = true, isAgent = true, ts = 2),
            room("!c3", peerId = "@chris:onion", paired = true, isAgent = true, ts = 1),
        ))
        assertEquals(setOf("!c1", "!c2", "!c3"), split.agentRooms.map { it.id }.toSet())
        assertTrue("agent rooms must never appear in Messaging", split.chats.isEmpty())
    }

    @Test
    fun `invites and pending-outgoing rows are never deduped`() {
        // An invite is actionable and a pending-outgoing row is a promise in flight —
        // hiding either behind a "canonical" room would strand the pairing.
        val split = RoomLists.partition(listOf(
            room("!live", peerId = "@friend:onion", paired = true),
            room("!invite", peerId = "@friend:onion", invited = true),
            room("!pending", peerId = "@friend:onion", outgoing = true),
        ))
        assertEquals(setOf("!live", "!invite", "!pending"), split.chats.map { it.id }.toSet())
    }

    @Test
    fun `rooms with no known peer are never deduped`() {
        // Heroes not warm yet -> peerId null. Deduping on null would collapse strangers.
        val split = RoomLists.partition(listOf(
            room("!x", peerId = null, paired = true),
            room("!y", peerId = null, paired = true),
        ))
        assertEquals(2, split.chats.size)
    }

    @Test
    fun `messaging shows people and the agents app shows agents`() {
        val split = RoomLists.partition(listOf(
            room("!human", peerId = "@friend:onion", paired = true, ts = 5),
            room("!ai", peerId = "@chris:onion", paired = true, isAgent = true, ts = 9),
        ))
        assertEquals(listOf("!human"), split.chats.map { it.id })
        assertEquals(listOf("!ai"), split.agentRooms.map { it.id })
    }

    @Test
    fun `chats order invites first then pending then recency`() {
        val split = RoomLists.partition(listOf(
            room("!old", peerId = "@a:o", paired = true, ts = 1),
            room("!new", peerId = "@b:o", paired = true, ts = 9),
            room("!inv", peerId = "@c:o", invited = true, ts = 0),
            room("!out", peerId = "@d:o", outgoing = true, ts = 0),
        ))
        assertEquals(listOf("!inv", "!out", "!new", "!old"), split.chats.map { it.id })
    }

    @Test
    fun `agent rooms order invites first then recency`() {
        val split = RoomLists.partition(listOf(
            room("!a1", isAgent = true, paired = true, ts = 1),
            room("!a2", isAgent = true, paired = true, ts = 9),
            room("!ainv", isAgent = true, invited = true, ts = 0),
        ))
        assertEquals(listOf("!ainv", "!a2", "!a1"), split.agentRooms.map { it.id })
    }

    @Test
    fun `two different peers never dedup against each other`() {
        val split = RoomLists.partition(listOf(
            room("!a", peerId = "@ann:onion", paired = true),
            room("!b", peerId = "@bob:onion", paired = true),
        ))
        assertEquals(2, split.chats.size)
    }
}
