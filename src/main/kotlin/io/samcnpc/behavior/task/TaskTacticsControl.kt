package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTactics
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** Change policy without refreshing the task identity, deadlines, attempts or completed effects. */
internal object TaskTacticsControl {
    fun current(record: TaskRecord): CombatTactics = when (val definition = record.primary.definition) {
        is AttackTaskDefinition -> definition.tactics
        is CombatMissionDefinition -> definition.tactics
        else -> record.reaction.policy.tactics
    }
    fun configure(server: MinecraftServer, npcUuid: UUID, change: (CombatTactics) -> CombatTactics): NpcActionResult {
        val store = TaskStore.forServer(server)
        val record = store.get(npcUuid) ?: return NpcActionResult.rejected("no assigned task", NpcActionCode.NOT_READY)
        if (record.status.terminal) return NpcActionResult.rejected("task already has a final report", NpcActionCode.NOT_READY)
        val tactics = change(current(record))
        val problem = tactics.validationProblem()
        if (problem != null) return NpcActionResult.rejected(problem)
        for (frame in record.frames) {
            frame.definition = when (val definition = frame.definition) {
                is AttackTaskDefinition -> definition.copy(version = 2, tactics = tactics)
                is DefendTaskDefinition -> definition.copy(tactics = tactics)
                is AreaAttackTaskDefinition -> definition.copy(tactics = tactics)
                is PatrolTaskDefinition -> definition.withTactics(tactics)
                else -> definition
            }
        }
        record.reaction.policy = record.reaction.policy.copy(tactics = tactics)
        BehaviorRuntimeService.releaseTaskControl(server, npcUuid)
        store.changed()
        return NpcActionResult.succeeded("combat tactics updated; original task progress and budgets retained: $tactics")
    }
}
