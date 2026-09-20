package io.samcnpc.behavior.runtime

import io.samcnpc.behavior.observation.OperationGenerationRegistry
import io.samcnpc.behavior.observation.OperationSubscriptions
import io.samcnpc.behavior.api.OperationSubscriptionState
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.EventPriority

import io.samcnpc.behavior.api.ValidationReport
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.task.TaskService
import io.samcnpc.behavior.model.ActionIntent
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionCompletedEvent
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.behavior.model.CompiledPack
import io.samcnpc.behavior.model.BehaviorReadContext
import io.samcnpc.behavior.registry.BehaviorDefinitions
import io.samcnpc.behavior.registry.BehaviorPackLoader
import io.samcnpc.behavior.registry.BehaviorRegistrySnapshot
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcRemovedEvent
import io.samcnpc.core.api.NpcServerTickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class NpcBehaviorDiagnostic(
    val activePacks: List<String>,
    val selectedIntents: List<String>,
    val lastProblem: String?,
    val gameTime: Long,
    val work: BehaviorWorkStatistics,
    val taskStatus: String?,
    val combatStatus: String?,
)

private data class RuntimeState(
    val metrics: BehaviorWorkMetrics,
    val cooldownUntil: MutableMap<String, Long> = mutableMapOf(),
    val actions: BehaviorActionScope = BehaviorActionScope(),
    var registry: BehaviorRegistrySnapshot? = null,
    var assignedIds: List<String> = emptyList(),
    var plan: BehaviorDecisionPlan? = null,
    var missingProblem: String? = null,
    var decision: BehaviorDecisionResult? = null,
    var lastGameTime: Long = 0L,
)

/** Runtime state is keyed by UUID only and cleared on removal/server stop; no Entity is retained. */
object BehaviorRuntimeService {
    private val loader = BehaviorPackLoader(BehaviorDefinitions.compiler)
    private val activeRegistry = AtomicReference(BehaviorRegistrySnapshot.EMPTY)
    private val runtimes: MutableMap<UUID, RuntimeState> = mutableMapOf()
    private var activeServer: net.minecraft.server.MinecraftServer? = null
    private val observationLifetimes = OperationGenerationRegistry()
    private val measureDecisions = java.lang.Boolean.getBoolean("samcnpc.behavior.profile")

    fun reloadAtStartup() {
        reload()
    }

    fun reload(): ValidationReport {
        val server = activeServer
        check(server == null || server.isSameThread) { "behavior reload requires the authoritative server thread" }
        val result = loader.loadCandidate()
        if (result.activated) {
            activeRegistry.set(result.snapshot)
            observationLifetimes.registryChanged()
            OperationSubscriptions.clear(OperationSubscriptionState.REGISTRY_RELOADED)
            if (server != null) {
                for (npcUuid in runtimes.keys.toList()) invalidate(server, npcUuid)
            }
        }
        return result.report
    }

    internal fun inspectionGenerations(server: net.minecraft.server.MinecraftServer, npcUuid: UUID,
                                       dimensionId: String, loadGeneration: UUID?, tick: Long):
        OperationGenerationRegistry.Result {
        if (activeServer !== server || !server.isSameThread) {
            return OperationGenerationRegistry.Result.Unavailable(
                OperationGenerationRegistry.Failure.NOT_STARTED)
        }
        return observationLifetimes.capture(npcUuid, dimensionId, loadGeneration, tick)
    }

    fun activePackIds(): List<String> = activeRegistry.get().packs.keys.sorted()

    fun externalDirectory() = loader.externalDirectory()

    /** The active authoritative server is needed by built-in actions with durable Behavior state. */
    internal fun serverOrNull(): net.minecraft.server.MinecraftServer? = activeServer

    fun diagnostic(npcUuid: UUID): NpcBehaviorDiagnostic? {
        val runtime = runtimes[npcUuid] ?: return null
        val decision = runtime.decision
        val server = activeServer
        val taskStatus = if (server == null) null else listOfNotNull(LumberjackService.status(server, npcUuid), TaskService.status(server, npcUuid)).joinToString(" | ").ifEmpty { null }
        return NpcBehaviorDiagnostic(runtime.assignedIds, decision?.selectedLabels ?: emptyList(),
            runtime.missingProblem ?: decision?.lastProblem, runtime.lastGameTime, runtime.metrics.snapshot(), taskStatus, BehaviorTargetMemory.diagnostic(npcUuid))
    }

    fun assignedPacks(server: net.minecraft.server.MinecraftServer, npcUuid: UUID): List<String> =
        BehaviorAssignmentStore.forServer(server).packsFor(npcUuid)

    fun assignPacks(server: net.minecraft.server.MinecraftServer, npcUuid: UUID, packIds: List<String>): NpcActionResult {
        check(server.isSameThread) { "behavior assignment requires the authoritative server thread" }
        val store = BehaviorAssignmentStore.forServer(server)
        val previous = store.packsFor(npcUuid)
        val result = store.replace(npcUuid, packIds)
        if (result.status == NpcActionStatus.SUCCEEDED && previous != packIds) {
            invalidate(server, npcUuid)
            TaskService.assignmentsChanged(server, npcUuid, packIds)
        }
        return result
    }

    private fun invalidate(server: net.minecraft.server.MinecraftServer, npcUuid: UUID) {
        val runtime = runtimes[npcUuid] ?: return
        val service = CoreNpcApi.service(server)
        val npc = service.find(npcUuid)?.let(service::runtime)
        if (npc == null) runtime.actions.forget() else release(runtime, npc)
        runtime.plan = null
        runtime.decision = null
        if (npc == null) BehaviorTargetMemory.remove(npcUuid) else BehaviorTargetMemory.reset(npc.snapshot())
    }

    internal fun releaseTaskControl(server: net.minecraft.server.MinecraftServer, npcUuid: UUID) {
        TaskService.released(npcUuid)
        val runtime = runtimes[npcUuid] ?: return
        val service = CoreNpcApi.service(server)
        val npc = service.find(npcUuid)?.let(service::runtime) ?: return
        runtime.actions.releaseMatching(npc, { TaskService.isTaskAction(it.action.actionId) }) { released(npcUuid, it) }
    }

    private fun released(npcUuid: UUID, intent: ActionIntent) {
        if (intent.action.actionId == FollowMovement.ACTION_ID) FollowMovement.remove(npcUuid)
        if (intent.action.actionId == "samcnpc:run_lumberjack_demo") LumberjackService.suspendExecution(npcUuid)
        if (TaskService.isTaskAction(intent.action.actionId)) TaskService.released(npcUuid)
    }

    private fun release(runtime: RuntimeState, npc: NpcFacade) {
        runtime.actions.clear(npc) { released(npc.npcUuid, it) }
    }

    @SubscribeEvent
    fun actionCompleted(event: NpcActionCompletedEvent) {
        runtimes[event.handle.npcUuid]?.actions?.completed(event.result)
        TaskService.actionCompleted(event)
    }

    @SubscribeEvent
    fun tick(event: NpcServerTickEvent) {
        val server = activeServer ?: return
        val runtime = runtimes.getOrPut(event.runtime.npcUuid) { RuntimeState(BehaviorWorkMetrics(measureDecisions)) }
        val started = runtime.metrics.begin()
        val planningTick = server.tickCount.toLong().and(0xffffffffL)
        BehaviorPlanning.advance(planningTick)
        val planning = BehaviorPlanning.forNpc(event.runtime.npcUuid, planningTick)
        val npc = MeasuredNpcFacade(event.runtime, runtime.metrics, planning)
        val world = MeasuredWorldView(event.world, runtime.metrics, planning)
        TaskService.observeTick(server, event.snapshot, npc, world)
        runtime.lastGameTime = event.snapshot.gameTime
        val registry = activeRegistry.get()
        val assignedIds = BehaviorAssignmentStore.forServer(server).packsFor(npc.npcUuid)
        val previous = runtime.plan
        val plan = if (previous == null || runtime.registry !== registry || runtime.assignedIds != assignedIds) {
            release(runtime, npc)
            val packs = ArrayList<CompiledPack>(assignedIds.size)
            val missing = ArrayList<String>()
            for (id in assignedIds) {
                val pack = registry.packs[id]
                if (pack == null) missing.add(id) else packs.add(pack)
            }
            val rebuilt = BehaviorDecisionPlan(packs)
            runtime.registry = registry
            runtime.assignedIds = java.util.List.copyOf(assignedIds)
            runtime.plan = rebuilt
            runtime.missingProblem = BehaviorAssignmentStore.forServer(server).problemFor(npc.npcUuid) ?:
                if (missing.isEmpty()) null else "Missing behavior pack(s): ${missing.sorted().joinToString(", ")}; safe idle active"
            runtime.cooldownUntil.clear()
            rebuilt
        } else {
            previous
        }
        val missingProblem = runtime.missingProblem
        if (missingProblem != null) {
            runtime.decision = null
            runtime.metrics.finish(null, started)
            return
        }
        val snapshot = event.snapshot
        val target = BehaviorTargetMemory.refresh(snapshot, world)
        val observedSummoner = if (snapshot.summonerUuid != null && snapshot.summonerUuid == target?.uuid) {
            target
        } else {
            snapshot.summonerUuid?.let(world::observeEntity)
        }
        val summoner = if (observedSummoner?.alive == true && observedSummoner.isPlayer) observedSummoner else null
        val context = BehaviorReadContext(snapshot, summoner, target, TaskService.isReady(server, npc.npcUuid),
            BehaviorTargetMemory.hasUnhandledDamage(snapshot), TaskService.isCombatReady(server, npc.npcUuid),
            TaskService.reactionRequested(server, snapshot, world), TaskService.isInventoryReady(server, npc.npcUuid),
            io.samcnpc.behavior.task.TaskLogistics.requested(server, npc, world))
        val decision = plan.tick(context, runtime.cooldownUntil, beforeExecution = { selected ->
            runtime.actions.prepare(selected, npc) { released(npc.npcUuid, it) }
        }) { intent ->
            if (runtime.actions.execution(intent) == null) {
                NpcActionResult.rejected("assignment changed during this decision; re-evaluate on the next tick")
            } else {
                intent.action.handler.execute(runtime.actions.facade(intent, npc), world, context)
            }
        }
        runtime.decision = decision
        runtime.metrics.finish(decision, started)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun observationTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.END) OperationSubscriptions.tick(event.server)
    }

    @SubscribeEvent
    fun actorLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        OperationSubscriptions.closeActor(event.entity.uuid)
    }

    @SubscribeEvent
    fun remove(event: NpcRemovedEvent) {
        OperationSubscriptions.closeNpc(event.handle.npcUuid, OperationSubscriptionState.NPC_UNAVAILABLE)
        observationLifetimes.remove(event.handle.npcUuid)
        BehaviorPlanning.release(event.handle.npcUuid)
        runtimes.remove(event.handle.npcUuid)
        BehaviorTargetMemory.remove(event.handle.npcUuid)
        FollowMovement.remove(event.handle.npcUuid)
        LumberjackService.suspendExecution(event.handle.npcUuid)
        val server = activeServer
        if (server != null) TaskService.removed(server, event.lifecycle)
    }

    @SubscribeEvent
    fun serverStarted(event: ServerStartedEvent) {
        OperationSubscriptions.clear(OperationSubscriptionState.SERVER_STOPPED)
        activeServer = event.server
        observationLifetimes.begin()
        // A mod constructor can run before userdev establishes its final game/resource paths.
        // Reload once the authoritative server is live so built-ins and server config activate on
        // both dedicated production servers and ForgeGradle command-line runs.
        reload()
    }

    @SubscribeEvent
    fun stop(event: ServerStoppingEvent) {
        OperationSubscriptions.clear(OperationSubscriptionState.SERVER_STOPPED)
        observationLifetimes.clear()
        for (npcUuid in runtimes.keys.toList()) invalidate(event.server, npcUuid)
        runtimes.clear()
        BehaviorTargetMemory.clearAll()
        FollowMovement.clearAll()
        TaskService.clearTransient()
        activeServer = null
    }

}
