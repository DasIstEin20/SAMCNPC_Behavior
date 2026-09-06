package io.samcnpc.behavior.runtime

import io.samcnpc.behavior.api.ValidationReport
import io.samcnpc.behavior.model.ActionIntent
import io.samcnpc.behavior.model.BehaviorArbiter
import io.samcnpc.behavior.model.CompiledPack
import io.samcnpc.behavior.model.ConditionExpression
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
)

private data class RuntimeState(
    val cooldownUntil: MutableMap<String, Long> = mutableMapOf(),
    var diagnostic: NpcBehaviorDiagnostic = NpcBehaviorDiagnostic(emptyList(), emptyList(), null),
)

/** Runtime state is keyed by UUID only and cleared on removal/server stop; no Entity is retained. */
object BehaviorRuntimeService {
    private val loader = BehaviorPackLoader(BehaviorDefinitions.compiler)
    private val activeRegistry = AtomicReference(BehaviorRegistrySnapshot.EMPTY)
    private val runtimes: MutableMap<UUID, RuntimeState> = mutableMapOf()
    private var activeServer: net.minecraft.server.MinecraftServer? = null

    fun reloadAtStartup() {
        reload()
    }

    fun reload(): ValidationReport {
        val result = loader.loadCandidate()
        if (result.activated) {
            activeRegistry.set(result.snapshot)
        }
        return result.report
    }

    fun activePackIds(): List<String> = activeRegistry.get().packs.keys.sorted()

    fun externalDirectory() = loader.externalDirectory()

    /** The active authoritative server is needed by built-in actions with durable Behavior state. */
    internal fun serverOrNull(): net.minecraft.server.MinecraftServer? = activeServer

    fun diagnostic(npcUuid: UUID): NpcBehaviorDiagnostic? = runtimes[npcUuid]?.diagnostic

    fun assignedPacks(server: net.minecraft.server.MinecraftServer, npcUuid: UUID): List<String> =
        BehaviorAssignmentStore.forServer(server).packsFor(npcUuid)

    fun assignPacks(server: net.minecraft.server.MinecraftServer, npcUuid: UUID, packIds: List<String>): NpcActionResult =
        BehaviorAssignmentStore.forServer(server).replace(npcUuid, packIds)

    @SubscribeEvent
    fun tick(event: NpcServerTickEvent) {
        val npc = event.runtime
        val server = activeServer ?: return
        val runtime = runtimes.getOrPut(npc.npcUuid) { RuntimeState() }
        val registry = activeRegistry.get()
        val assignedIds = BehaviorAssignmentStore.forServer(server).packsFor(npc.npcUuid)
        val packs = ArrayList<CompiledPack>(assignedIds.size)
        val missing = mutableListOf<String>()
        for (id in assignedIds) {
            val pack = registry.packs[id]
            if (pack == null) {
                missing.add(id)
            } else {
                packs.add(pack)
            }
        }
        if (missing.isNotEmpty()) {
            runtime.diagnostic = NpcBehaviorDiagnostic(assignedIds, emptyList(), "Missing behavior pack(s): ${missing.sorted().joinToString(", ")}; safe idle active")
            return
        }
        val snapshot = event.snapshot
        val intents = mutableListOf<ActionIntent>()
        for (pack in packs.sortedBy { it.id }) {
            for (rule in pack.rules) {
                val cooldownKey = "${pack.id}/${rule.id}"
                if ((runtime.cooldownUntil[cooldownKey] ?: Long.MIN_VALUE) > snapshot.gameTime) {
                    continue
                }
                if (!evaluate(rule.whenExpression, npc, event.world, snapshot)) {
                    continue
                }
                rule.actions.forEachIndexed { actionIndex, action ->
                    intents.add(ActionIntent(pack.id, pack.priority, rule.id, rule.priority, actionIndex, action))
                }
            }
        }
        val selected = BehaviorArbiter.choose(intents)
        val selectedLabels = ArrayList<String>(selected.size)
        var problem: String? = null
        for (intent in selected) {
            val definition = BehaviorDefinitions.actions.getValue(intent.action.actionId)
            val result = definition.handler.execute(npc, event.world, intent.action.args)
            selectedLabels.add("${intent.packId}/${intent.ruleId}/${intent.action.actionId}:${result.status}")
            if (result.status != io.samcnpc.core.api.NpcActionStatus.REJECTED && result.status != io.samcnpc.core.api.NpcActionStatus.FAILED) {
                val rule = packs.first { it.id == intent.packId }.rules.first { it.id == intent.ruleId }
                if (rule.cooldownTicks > 0) {
                    runtime.cooldownUntil["${intent.packId}/${intent.ruleId}"] = snapshot.gameTime + rule.cooldownTicks
                }
            } else {
                problem = "${intent.packId}/${intent.ruleId}: ${result.detail}"
            }
        }
        runtime.diagnostic = NpcBehaviorDiagnostic(assignedIds, selectedLabels, problem)
    }

    @SubscribeEvent
    fun remove(event: NpcRemovedEvent) {
        runtimes.remove(event.handle.npcUuid)
        BehaviorTargetMemory.remove(event.handle.npcUuid)
    }

    @SubscribeEvent
    fun serverStarted(event: ServerStartedEvent) {
        activeServer = event.server
        // A mod constructor can run before userdev establishes its final game/resource paths.
        // Reload once the authoritative server is live so built-ins and server config activate on
        // both dedicated production servers and ForgeGradle command-line runs.
        reload()
    }

    @SubscribeEvent
    fun stop(event: ServerStoppingEvent) {
        runtimes.clear()
        BehaviorTargetMemory.clearAll()
        activeServer = null
    }

    private fun evaluate(
        expression: ConditionExpression,
        npc: io.samcnpc.core.api.NpcFacade,
        world: io.samcnpc.core.api.NpcWorldView,
        snapshot: io.samcnpc.core.api.NpcSnapshot,
    ): Boolean = when (expression) {
        is ConditionExpression.Test -> BehaviorDefinitions.conditions.getValue(expression.conditionId).handler.evaluate(npc, world, snapshot, expression.args)
        is ConditionExpression.All -> expression.children.all { evaluate(it, npc, world, snapshot) }
        is ConditionExpression.Any -> expression.children.any { evaluate(it, npc, world, snapshot) }
        is ConditionExpression.Not -> !evaluate(expression.child, npc, world, snapshot)
    }
}
