package io.samcnpc.behavior.task

import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** Durable task controls; only executeSelected invokes a registered operation's world actions. */
internal object TaskService {
    const val PACK_ID = "samcnpc:task_navigation"
    const val ACTION_ID = "samcnpc:run_navigation_task"
    const val DELIVERY_PACK_ID = "samcnpc:task_delivery"
    const val DELIVERY_ACTION_ID = "samcnpc:run_delivery_task"
    const val LUMBERJACK_PACK_ID = "samcnpc:task_lumberjack"
    const val LUMBERJACK_ACTION_ID = "samcnpc:run_lumberjack_task"
    const val COMBAT_PACK_ID = "samcnpc:task_combat"
    const val COMBAT_ACTION_ID = "samcnpc:run_combat_task"
    const val REACTION_ACTION_ID = "samcnpc:begin_task_reaction"
    fun isTaskAction(id: String): Boolean = id == ACTION_ID || id == DELIVERY_ACTION_ID || id == LUMBERJACK_ACTION_ID || id == COMBAT_ACTION_ID || id == REACTION_ACTION_ID
    private fun packFor(definition: TaskDefinition): String = when (definition) {
        is AttackTaskDefinition -> COMBAT_PACK_ID
        is NavigateTaskDefinition -> PACK_ID
        is DeliveryTaskDefinition -> DELIVERY_PACK_ID
        is LumberjackTaskDefinition -> LUMBERJACK_PACK_ID
    }
    private data class Clock(val taskId: UUID, var lastTick: Long)
    private val clocks = mutableMapOf<UUID, Clock>()
    private val executions = mutableMapOf<UUID, TaskExecution>()

    fun assign(server: MinecraftServer, npc: NpcFacade, definition: TaskDefinition): NpcActionResult {
        check(server.isSameThread)
        val problem = definition.validationProblem()
        if (problem != null) return NpcActionResult.rejected(problem)
        val snapshot = npc.snapshot()
        if (definition.dimensionId != snapshot.dimensionId) return NpcActionResult.rejected("task must use the NPC's current dimension")
        if (definition is NavigateTaskDefinition && TaskNavigator.distanceSquared(snapshot.position, definition.destination) > 64.0 * 64.0) {
            return NpcActionResult.rejected("navigation task destination must be within 64 blocks")
        }
        if (definition is AttackTaskDefinition) {
            val area = io.samcnpc.behavior.combat.CombatTargetSelector.Area(definition.anchor, definition.leash)
            if (io.samcnpc.behavior.combat.CombatTargetSelector.exact(snapshot, npc.worldView(), definition.targetUuid, area,
                    definition.allowPlayers, requireVisible = false) == null) return NpcActionResult.rejected("exact attack target is unavailable or outside the permitted policy/leash", NpcActionCode.PERMISSION_DENIED)
        }
        val packId = packFor(definition)
        if (packId !in BehaviorRuntimeService.activePackIds()) return NpcActionResult.rejected("task pack is unavailable: $packId", NpcActionCode.NOT_READY)
        val legacy = LumberjackDemoStore.forServer(server)
        val legacyProblem = legacy.problemFor(npc.npcUuid)
        if (legacyProblem != null) return NpcActionResult.rejected(legacyProblem, NpcActionCode.NOT_READY)
        if (legacy.jobFor(npc.npcUuid) != null) return NpcActionResult.rejected("NPC already has a lumberjack task", NpcActionCode.CONFLICT)
        val resources = if (definition is DeliveryTaskDefinition) {
            TaskDelivery.capture(npc, definition) ?: return NpcActionResult.rejected("delivery requires an observable loaded container with at most 64 slots", NpcActionCode.NOT_FOUND)
        } else null
        val unresolved = UnresolvedWorkStore.forServer(server)
        if (definition is LumberjackTaskDefinition && unresolved.problem != null) return NpcActionResult.rejected(checkNotNull(unresolved.problem), NpcActionCode.NOT_READY)
        val lumberjack = if (definition is LumberjackTaskDefinition) {
            TaskLumberjack.capture(npc, definition) ?: return NpcActionResult.rejected("lumberjack requires an observable container and bounded inventory", NpcActionCode.NOT_READY)
        } else null
        val prior = TaskStore.forServer(server).get(npc.npcUuid)
        if (prior?.status?.terminal == true) {
            val preserved = TaskLumberjack.preserve(server, prior, npc.worldView())
            if (preserved.status != NpcActionStatus.SUCCEEDED) return preserved
        }
        val record = TaskRecord.start(npc.npcUuid, definition, BehaviorRuntimeService.assignedPacks(server, npc.npcUuid))
        record.primary.resources = resources
        record.primary.lumberjack = lumberjack
        val store = TaskStore.forServer(server)
        val added = store.put(record)
        if (added.status != NpcActionStatus.SUCCEEDED) return added
        val assignment = BehaviorRuntimeService.assignPacks(server, npc.npcUuid, listOf(packId))
        if (assignment.status != NpcActionStatus.SUCCEEDED) {
            record.finish(TaskStatus.FAILED, TaskReason.STATE_MISMATCH, "task assignment failed: ${assignment.detail}")
            store.changed()
            return assignment
        }
        executions.remove(npc.npcUuid)
        clocks[npc.npcUuid] = Clock(record.id, snapshot.gameTime)
        return added
    }

    fun pause(server: MinecraftServer, npcUuid: UUID): NpcActionResult = control(server, npcUuid) { record ->
        if (!record.pause()) return@control NpcActionResult.rejected("task is not running or waiting", NpcActionCode.NOT_READY)
        BehaviorRuntimeService.releaseTaskControl(server, npcUuid)
        NpcActionResult.succeeded(record.detail)
    }

    fun resume(server: MinecraftServer, npcUuid: UUID): NpcActionResult = control(server, npcUuid) { record ->
        if (packFor(record.primary.definition) !in BehaviorRuntimeService.assignedPacks(server, npcUuid)) return@control NpcActionResult.rejected("task pack is no longer assigned", NpcActionCode.NOT_READY)
        if (!record.resume()) return@control NpcActionResult.rejected("task is not paused", NpcActionCode.NOT_READY)
        executions.remove(npcUuid)
        NpcActionResult.succeeded(record.detail)
    }

    fun cancel(server: MinecraftServer, npcUuid: UUID): NpcActionResult = control(server, npcUuid) { record ->
        if (record.status.terminal) return@control NpcActionResult.rejected("task already has a final report", NpcActionCode.NOT_READY)
        record.finish(TaskStatus.CANCELLED, TaskReason.USER_CANCELLED, "cancelled; completed movement/world effects are retained")
        finishControl(server, record)
        NpcActionResult.succeeded(record.detail)
    }

    fun interrupt(server: MinecraftServer, npcUuid: UUID, definition: TaskDefinition): NpcActionResult = control(server, npcUuid) { record ->
        val problem = record.interrupt(definition)
        if (problem != null) return@control NpcActionResult.rejected(problem, NpcActionCode.CONFLICT)
        BehaviorRuntimeService.releaseTaskControl(server, npcUuid)
        NpcActionResult.succeeded(record.detail)
    }

    fun isReady(server: MinecraftServer, npcUuid: UUID): Boolean = TaskStore.forServer(server).get(npcUuid)?.status == TaskStatus.RUNNING
    fun isCombatReady(server: MinecraftServer, npcUuid: UUID): Boolean {
        val record = TaskStore.forServer(server).get(npcUuid) ?: return false
        return record.status == TaskStatus.RUNNING && (record.active.definition is AttackTaskDefinition || record.primary.definition is AttackTaskDefinition)
    }
    fun reactionRequested(server: MinecraftServer, snapshot: NpcSnapshot): Boolean = TaskCombatReactions.requested(TaskStore.forServer(server).get(snapshot.npcUuid), snapshot)

    fun status(server: MinecraftServer, npcUuid: UUID): String? {
        val store = TaskStore.forServer(server)
        val problem = store.problemFor(npcUuid)
        if (problem != null) return problem
        val record = store.get(npcUuid) ?: return null
        val report = record.report()
        val destination = when (val definition = record.active.definition) {
            is AttackTaskDefinition -> "target=${definition.targetUuid}; anchor=${definition.anchor}; leash=${definition.leash}"
            is NavigateTaskDefinition -> definition.destination.toString()
            is DeliveryTaskDefinition -> definition.destination.toString()
            is LumberjackTaskDefinition -> definition.destination.toString()
        }
        val delivery = record.primary.definition as? DeliveryTaskDefinition
        val resources = if (delivery == null) woodStatus(record) else "item=${delivery.itemId}; reserve=${delivery.keepAtLeast}; ${record.primary.resources?.describe(delivery.quantity)}; "
        return "task=${report.taskId}; operation=${report.operationId}; state=${report.status}; reason=${report.reason}; " +
            "destination=$destination; remainingTicks=${report.remainingTicks}; failures=${report.failures}; " +
            "waitTicks=${record.active.waitTicks}; interruptionDepth=${report.interruptedDepth}/2; " +
            "completedInterruptions=${report.completedInterruptions}; execution=${executions[npcUuid]?.generation}; " +
            "observed=${record.reconciledPosition}; reaction=${record.reaction.policy.mode}; reactionCooldown=${record.reaction.cooldownRemaining}; " +
            "lastCombat=${record.lastCombat}; $resources${report.detail}"
    }

    /** Time bookkeeping observes every NPC tick, even when another intent owns the channels. */
    fun observeTick(server: MinecraftServer, snapshot: NpcSnapshot, npc: NpcFacade? = null) {
        val store = TaskStore.forServer(server)
        val record = store.get(snapshot.npcUuid) ?: return
        if (record.status.terminal) return
        TaskCombatReactions.observe(record, snapshot)
        val previousFrame = record.active.id
        if (npc != null) {
            val resourceProblem = TaskLumberjack.observeInventory(record, npc)
            if (resourceProblem != null) record.finish(TaskStatus.FAILED, TaskReason.STATE_MISMATCH, resourceProblem)
        }
        val existing = clocks[snapshot.npcUuid]
        val clock = if (existing == null || existing.taskId != record.id) {
            Clock(record.id, snapshot.gameTime).also { clocks[snapshot.npcUuid] = it }
        } else existing
        val elapsed = snapshot.gameTime - clock.lastTick
        clock.lastTick = snapshot.gameTime
        if (elapsed < 0) record.finish(TaskStatus.FAILED, TaskReason.STATE_MISMATCH, "world clock moved backwards during task execution")
        else record.advanceTime(elapsed.coerceAtMost(72000).toInt())
        if (snapshot.dimensionId != record.primary.definition.dimensionId) record.finish(TaskStatus.FAILED, TaskReason.DIMENSION_CHANGED, "NPC changed dimension")
        if (elapsed != 0L || record.status.terminal) store.changed()
        if (record.active.id != previousFrame) BehaviorRuntimeService.releaseTaskControl(server, snapshot.npcUuid)
        if (record.status.terminal) finishControl(server, record)
    }

    fun executeSelected(server: MinecraftServer, npc: NpcFacade, world: NpcWorldView, actionId: String = ACTION_ID): NpcActionResult {
        val store = TaskStore.forServer(server)
        val record = store.get(npc.npcUuid) ?: return NpcActionResult.rejected(store.problemFor(npc.npcUuid) ?: "no assigned task", NpcActionCode.NOT_READY)
        val expectedAction = if (record.active.definition is AttackTaskDefinition) COMBAT_ACTION_ID else when (record.primary.definition) {
            is AttackTaskDefinition -> COMBAT_ACTION_ID
            is NavigateTaskDefinition -> ACTION_ID
            is DeliveryTaskDefinition -> DELIVERY_ACTION_ID
            is LumberjackTaskDefinition -> LUMBERJACK_ACTION_ID
        }
        if (actionId != expectedAction) return NpcActionResult.rejected("task action does not declare the required operation channels", NpcActionCode.CONFLICT)
        if (record.status.terminal) return NpcActionResult.rejected("task has a final report: ${record.status}", NpcActionCode.NOT_READY)
        if (record.status != TaskStatus.RUNNING) return NpcActionResult.running("task ${record.status}: ${record.reason}")
        val old = executions[npc.npcUuid]
        val execution = if (old == null || old.taskId != record.id || old.frameId != record.active.id) {
            TaskExecution(record.id, record.active.id).also { executions[npc.npcUuid] = it }
        } else old
        val frameId = record.active.id
        val result = when (record.active.definition) {
            is AttackTaskDefinition -> TaskCombat.tick(record, execution, npc, world)
            is NavigateTaskDefinition -> TaskNavigator.tick(record, execution, npc, world)
            is DeliveryTaskDefinition -> TaskDelivery.tick(record, execution, npc, world)
            is LumberjackTaskDefinition -> TaskLumberjack.tick(server, record, npc, world)
        }
        store.changed()
        if (record.status != TaskStatus.RUNNING || frameId != record.active.id) BehaviorRuntimeService.releaseTaskControl(server, npc.npcUuid)
        if (record.status.terminal) finishControl(server, record)
        return result
    }

    fun actionCompleted(event: NpcActionCompletedEvent) {
        val execution = executions[event.handle.npcUuid] ?: return
        val expected = execution.navigationId ?: return
        if (event.result.actionId == expected) execution.completion = event.result
    }

    fun released(npcUuid: UUID) {
        executions.remove(npcUuid)
        val server = BehaviorRuntimeService.serverOrNull() ?: return
        val state = TaskStore.forServer(server).get(npcUuid)?.primary?.lumberjack ?: return
        TaskLumberjack.suspend(state)
    }

    fun assignmentsChanged(server: MinecraftServer, npcUuid: UUID, packIds: List<String>) {
        val store = TaskStore.forServer(server)
        val record = store.get(npcUuid) ?: return
        if (packFor(record.primary.definition) in packIds) return
        if (!record.status.terminal) {
            record.finish(TaskStatus.CANCELLED, TaskReason.ASSIGNMENT_CHANGED, "task pack was removed; completed effects retained")
            store.changed()
        }
        executions.remove(npcUuid)
        preserveWork(server, record)
    }

    fun removed(server: MinecraftServer, lifecycle: NpcLifecycleSnapshot) {
        val npcUuid = lifecycle.handle.npcUuid
        executions.remove(npcUuid)
        clocks.remove(npcUuid)
        val store = TaskStore.forServer(server)
        val record = store.get(npcUuid) ?: return
        record.primary.lumberjack?.let {
            TaskLumberjack.suspend(it)
            it.resources.mustReconcileLoad = true
        }
        if (lifecycle.state == NpcLifecycleState.UNLOADED) return
        if (!record.status.terminal) {
            record.finish(TaskStatus.CANCELLED, TaskReason.NPC_REMOVED, "NPC removed: ${lifecycle.state}")
            TaskLumberjack.preserve(server, record, null)
            store.changed()
        }
    }

    fun clearTransient() { executions.clear(); clocks.clear() }

    private fun finishControl(server: MinecraftServer, record: TaskRecord) {
        preserveWork(server, record)
        BehaviorRuntimeService.releaseTaskControl(server, record.npcUuid)
        if (BehaviorRuntimeService.assignedPacks(server, record.npcUuid) == listOf(packFor(record.primary.definition))) {
            BehaviorRuntimeService.assignPacks(server, record.npcUuid, record.previousPacks)
        }
    }

    private fun preserveWork(server: MinecraftServer, record: TaskRecord) {
        if (record.primary.lumberjack == null) return
        val service = CoreNpcApi.service(server)
        val npc = service.find(record.npcUuid)?.let(service::runtime)
        val result = TaskLumberjack.preserve(server, record, npc?.worldView())
        if (result.status != NpcActionStatus.SUCCEEDED) record.detail = (record.detail + "; " + result.detail).take(TaskRecord.MAX_DETAIL_LENGTH)
        TaskStore.forServer(server).changed()
    }

    private fun woodStatus(record: TaskRecord): String {
        val definition = record.primary.definition as? LumberjackTaskDefinition ?: return ""
        val state = record.primary.lumberjack ?: return ""
        return "wood=${definition.wood.selectors}; area=${definition.area.bounds}; exclusions=${definition.area.exclusions.size}; " +
            "phase=${state.job.phase}; scanColumns=${state.job.scanCursor}/${definition.area.columns}; " +
            "${state.resources.describe(definition.wood, definition.quantity)}; residuePreserved=${state.residuePreserved}; "
    }

    private fun control(server: MinecraftServer, npcUuid: UUID, change: (TaskRecord) -> NpcActionResult): NpcActionResult {
        check(server.isSameThread)
        val store = TaskStore.forServer(server)
        val record = store.get(npcUuid) ?: return NpcActionResult.rejected(store.problemFor(npcUuid) ?: "NPC has no durable task", NpcActionCode.NOT_READY)
        val result = change(record)
        if (result.status == NpcActionStatus.SUCCEEDED) store.changed()
        return result
    }
}
