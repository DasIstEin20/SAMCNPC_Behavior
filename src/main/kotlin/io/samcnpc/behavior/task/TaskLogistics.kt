package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer

/** Captures readiness outside conditions; only its registered begin action changes the frame stack. */
internal object TaskLogistics {
    private data class Capture(val task: java.util.UUID, val revision: Int, val tick: Long, val definition: InventoryTaskDefinition?)
    private val captures = mutableMapOf<java.util.UUID, Capture>()
    fun remove(npc: java.util.UUID) { captures.remove(npc) }
    fun clear() { captures.clear() }
    fun candidate(record: TaskRecord?, npc: NpcFacade, world: NpcWorldView): InventoryTaskDefinition? {
        if (record == null || record.status != TaskStatus.RUNNING || record.frames.size != 1 || record.completedInterruptions >= 32 ||
            record.logistics.cooldownRemaining > 0 || !record.logistics.policy.enabled || record.amendments.pending != null ||
            record.primary.definition is InventoryTaskDefinition || record.primary.definition is AttackTaskDefinition || record.primary.definition is CombatMissionDefinition) return null
        if (!TaskAmendmentPreparation.boundary(record, TaskChange.Replace(record.primary.definition))) return null
        val snapshot = npc.snapshot()
        val cached = captures[npc.npcUuid]
        if (cached != null && cached.task == record.id && cached.revision == record.amendments.revision && cached.tick == snapshot.gameTime) return cached.definition
        val policy = record.logistics.policy; val anchor = checkNotNull(policy.anchor)
        if (!snapshot.onGround || snapshot.dimensionId != record.primary.definition.dimensionId ||
            TaskNavigator.distanceSquared(anchor, snapshot.position) > policy.travelRadius * policy.travelRadius) return null
        val counts = HarvestResources.inventoryCounts(npc)
        val unload = policy.unload?.takeIf { work -> work.reserves.any { !InventoryTaskCapture.protected(record, it.itemId) && InventoryTaskCapture.unloadable(npc, it.itemId, it.keep) > 0 } }
        val supply = policy.supply?.takeIf { work -> work.needs.any { (counts[it.itemId] ?: 0) < it.minimum } }
        var chosen: InventoryWork? = unload ?: supply
        val pickup = policy.pickup
        if (chosen == null && pickup != null && BehaviorPlanning.admit(world, 33, PlanningKind.PICKUP) &&
            InventoryPickupWork.candidates(record, npc, world, pickup, snapshot.position, pickup.maxItems).isNotEmpty()) chosen = pickup
        val result = if (chosen == null) null else InventoryTaskDefinition(snapshot.dimensionId, chosen, anchor, snapshot.position, policy.travelRadius,
            policy.workTicks, policy.maxSteps, TaskBudget(policy.durationTicks))
        if (captures.size < 4096 || npc.npcUuid in captures) captures[npc.npcUuid] = Capture(record.id, record.amendments.revision, snapshot.gameTime, result)
        return result
    }
    fun requested(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView): Boolean = candidate(TaskStore.forServer(server).get(npc.npcUuid), npc, world) != null
    fun begin(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView): NpcActionResult {
        val store = TaskStore.forServer(server); val record = store.get(npc.npcUuid)
        val definition = candidate(record, npc, world) ?: return NpcActionResult.running("no eligible bounded inventory interruption")
        return TaskService.interrupt(server, npc.npcUuid, definition)
    }
    fun revise(record: TaskRecord, npc: NpcFacade, policy: TaskLogisticsPolicy): String? {
        val problem = policy.validationProblem(); if (problem != null) return problem
        if (record.primary.definition is InventoryTaskDefinition || record.primary.definition is AttackTaskDefinition || record.primary.definition is CombatMissionDefinition) return "logistics policy belongs to ordinary work, not standalone inventory/combat"
        if (record.frames.size != 1) return "finish the current interruption before changing its logistics policy"
        if (policy.anchor != null && TaskNavigator.distanceSquared(policy.anchor, npc.snapshot().position) > policy.travelRadius * policy.travelRadius) return "NPC is outside the proposed fixed logistics boundary"
        record.logistics.policy = policy
        return null
    }
}
