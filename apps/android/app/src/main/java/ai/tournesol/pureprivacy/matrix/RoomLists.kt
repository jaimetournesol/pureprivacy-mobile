package ai.tournesol.pureprivacy.matrix

/**
 * The pure half of [MatrixRepo.rebuildRooms]: which agent (if any) a room belongs to, which
 * duplicate DMs to hide, and the human/AI partition. No SDK types on purpose — everything
 * here is a function of plain values, so it runs under plain JVM unit tests.
 *
 * This logic has already regressed twice, silently, in one week: the DM dedup collapsed an
 * agent's several conversations down to the oldest one, and a just-created conversation
 * rendered in Messaging as a chat with a stranger until the agent joined. Neither showed an
 * error — the room list simply looked plausible and was wrong. That is exactly the failure
 * mode unit tests exist for, hence the extraction.
 */
object RoomLists {

    /**
     * Resolve the agent a room is a conversation with, or null for a human room.
     *
     * Order matters and each rung exists for a reason:
     *  1. The box's session list ([sessionAgentIds]) — covers EVERY conversation of an
     *     agent, including one created seconds ago that the agent hasn't joined yet.
     *     Cheapest and most certain, so it goes first.
     *  2. The registry's published room id — what recognises the MAIN room of a freshly
     *     provisioned agent that is invited but not yet joined (not a hero, so peer-id
     *     matching misses it).
     *  3. The room's counterpart user id — the steady-state match once the agent has
     *     joined and become a hero.
     */
    fun agentFor(
        roomId: String,
        peerId: String?,
        sessionAgentIds: Map<String, String>,
        agents: Map<String, MatrixRepo.AgentInfo>,
    ): MatrixRepo.AgentInfo? =
        sessionAgentIds[roomId]?.let { agents[it] }
            ?: agents.values.firstOrNull { it.roomId.isNotBlank() && it.roomId == roomId }
            ?: peerId?.let { agents[it] }

    /** The two lists the UI renders: Messaging (people) and the Agents app (AIs). */
    data class Partition(
        val chats: List<RoomSummary>,
        val agentRooms: List<RoomSummary>,
    )

    /**
     * Dedup + sort + split.
     *
     * Dedup: a human peer can end up with >1 live room (create-race, or a re-add before the
     * first invite landed). Show only ONE — deterministically the LOWEST room-id, which both
     * sides pick the same, so they converge on it and the extra room goes quiet. Only
     * paired human rooms with a known peer are deduped; invites and pending-outgoing rows
     * never are.
     *
     * AGENTS ARE EXEMPT. Several live rooms with one peer is a fault for a person — for an
     * agent it is the feature: each room is a separate conversation with its own history.
     * Deduping them would silently hide every conversation but the oldest, which looks
     * exactly like the box losing them.
     *
     * The partition is the whole guarantee of the Agents app: if agent rooms ever leak into
     * [Partition.chats], an AI turns up in Messaging next to real contacts. It stays the
     * last thing that touches the list.
     */
    fun partition(all: List<RoomSummary>): Partition {
        val canonical = HashMap<String, String>()
        for (s in all) {
            val p = s.peerId
            if (s.paired && !s.isAgent && p != null) {
                val cur = canonical[p]
                if (cur == null || s.id < cur) canonical[p] = s.id
            }
        }
        // Invites first (actionable), then pending outgoing, then most-recently-active.
        val visible = all
            .filter { s -> val p = s.peerId; s.isAgent || !s.paired || p == null || canonical[p] == s.id }
            .sortedWith(
                compareByDescending<RoomSummary> { it.invited }
                    .thenByDescending { it.outgoing }
                    .thenByDescending { it.ts }
            )
        val (agentSide, humanSide) = visible.partition { it.isAgent }
        return Partition(
            chats = humanSide,
            agentRooms = agentSide.sortedWith(
                compareByDescending<RoomSummary> { it.invited }.thenByDescending { it.ts }
            ),
        )
    }
}
