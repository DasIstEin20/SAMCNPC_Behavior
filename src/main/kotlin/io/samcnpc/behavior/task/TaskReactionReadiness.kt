package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*
import java.util.UUID

/** One captured area search per decision; selected actions revalidate the exact UUID with Core. */
internal object TaskReactionReadiness {
    private data class Search(val task: UUID, val revision: Int, val policy: TaskReactionPolicy, var nextSearch: Long = 0, var tick: Long = -1,
                              var candidate: TaskReactionSelection.Candidate? = null)
    private val searches = mutableMapOf<UUID, Search>()

    fun candidate(record: TaskRecord?, snapshot: NpcSnapshot, world: NpcWorldView): TaskReactionSelection.Candidate? {
        if (record == null || record.reaction.policy.mode != TaskReactionMode.AREA) {
            searches.remove(snapshot.npcUuid)
            return TaskReactionSelection.candidate(record, snapshot, world)
        }
        if (record.status.terminal) { searches.remove(snapshot.npcUuid); return null }
        if (!TaskReactionSelection.canInterrupt(record) || record.reaction.cooldownRemaining != 0) return null
        var state = searches[snapshot.npcUuid]
        if (state == null || state.task != record.id || state.revision != record.amendments.revision || state.policy != record.reaction.policy || snapshot.gameTime < state.tick) {
            if (state == null && searches.size >= 4096) return null
            state = Search(record.id, record.amendments.revision, record.reaction.policy)
            searches[snapshot.npcUuid] = state
        }
        if (state.tick == snapshot.gameTime) return state.candidate
        if (snapshot.gameTime < state.nextSearch) return null
        if (!BehaviorPlanning.admit(world, 65, PlanningKind.REACTION_SEARCH)) return null
        state.tick = snapshot.gameTime
        state.nextSearch = snapshot.gameTime + 10
        state.candidate = TaskReactionSelection.candidate(record, snapshot, world, scheduledAreaSearch = true)
        return state.candidate
    }
    fun remove(npc: UUID) { searches.remove(npc) }
    fun clear() = searches.clear()
}
