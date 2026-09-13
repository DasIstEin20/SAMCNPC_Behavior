package io.samcnpc.behavior.operations

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.TaskNavigator
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcFacade
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Mod.EventBusSubscriber(modid = SamcnpcBehavior.MOD_ID)
object OperationsLifecycleSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.operationsLifecycle")
    private val logger = com.mojang.logging.LogUtils.getLogger()
    private val report = Path.of("operations-lifecycle.txt")
    private var author: OperationAuthoringScenario? = null
    private var reload: OperationReloadScenario? = null
    private var removals = emptyList<OperationRemovalScenario>()
    private var connection: OperationConnectionScenario? = null
    private var protections = emptyList<OperationProtectionScenario>()
    private var miningAdversity = emptyList<OperationMiningAdversityScenario>()
    private var stopScene: OperationScene? = null
    private var staleOnStop: NpcFacade? = null
    private val ids = mutableSetOf<UUID>()
    private val results = mutableListOf<String>()
    private var phase = -1
    private var ticks = 0
    private var started = false
    private var failed = false
    private var awaitingStop = false

    @SubscribeEvent fun startedLifecycle(event: ServerStartedEvent) {
        if (!enabled) return
        guarded(event.server) {
            check(event.server.isDedicatedServer && event.server.playerCount == 0)
            OperationCases.configure(event.server)
            val scenario = OperationAuthoringScenario(event.server)
            author = scenario
            ids.add(scenario.npcId)
            started = true
        }
    }

    @SubscribeEvent fun tickLifecycle(event: TickEvent.ServerTickEvent) {
        if (!enabled || !started || failed || awaitingStop || event.phase != TickEvent.Phase.END) return
        guarded(event.server) {
            check(++ticks < 2400) { "Lifecycle campaign timed out phase=$phase" }
            when (phase) {
                -1 -> {
                    val scenario = checkNotNull(author)
                    scenario.tick()
                    if (scenario.complete) {
                        results.add(scenario.report)
                        author = null
                        val next = OperationReloadScenario(event.server)
                        reload = next
                        ids.add(next.scene.npcId)
                        phase = 0
                    }
                }
                0 -> {
                    val scenario = checkNotNull(reload)
                    scenario.tick()
                    if (scenario.complete) {
                        results.add("disk reload: rejected candidate preserves action/task; accepted candidate rebuilds; missing assigned pack idles; restored pack completes original task")
                        reload = null
                        removals = OperationRemovalKind.entries.map { OperationRemovalScenario(event.server, it) }
                        ids.addAll(removals.map { it.scene.npcId })
                        val joined = OperationConnectionScenario(event.server)
                        connection = joined
                        ids.add(joined.npcId)
                        phase = 1
                    }
                }
                1 -> {
                    for (scenario in removals) scenario.tick()
                    checkNotNull(connection).tick()
                    if (removals.all { it.complete } && checkNotNull(connection).complete) {
                        results.add("native death/dismiss/dimension recreation: death/dismiss cancelled, dimension explicitly failed DIMENSION_CHANGED, stale facades rejected, death harvest reservation released, dimension UUID preserved")
                        results.add("summoner PlayerList removal/re-entry: UUID binding retained, follow stops then rebuilds a fresh route; no authenticated skin claim")
                        removals = emptyList()
                        connection = null
                        protections = OperationProtectionKind.entries.map { OperationProtectionScenario(event.server, it) }
                        ids.addAll(protections.flatMap { it.npcIds })
                        miningAdversity = OperationMiningAdversityKind.entries.map { OperationMiningAdversityScenario(event.server, it) }
                        ids.addAll(miningAdversity.map { it.scene.npcId })
                        phase = 2
                    }
                }
                2 -> {
                    for (scenario in protections) scenario.tick()
                    for (scenario in miningAdversity) scenario.tick()
                    if (protections.all { it.complete } && miningAdversity.all { it.complete }) {
                        for (scenario in protections) results.add("${scenario.kind}: actual selected enemy defeated; protected subject/bystander unharmed; duty/route completed and returned")
                        for (scenario in miningAdversity) results.add("mining ${scenario.kind}: bounded explicit failure, actual world/stock/tool/partial delivery conserved, controls released")
                        miningAdversity = emptyList()
                        protections = emptyList()
                        val scene = OperationScene.create(event.server.overworld(), OperationKind.NAVIGATE, BlockPos(1650, 80, 1000))
                        stopScene = scene
                        ids.add(scene.npcId)
                        phase = 3
                    }
                }
                3 -> {
                    val scene = checkNotNull(stopScene)
                    if (scene.loaded && scene.body.onGround()) {
                        OperationCases.prepare(scene)
                        phase = 4
                    }
                }
                4 -> {
                    val scene = checkNotNull(stopScene)
                    val snapshot = scene.npc.snapshot()
                    val navigation = snapshot.navigation
                    if (navigation != null && TaskNavigator.distanceSquared(snapshot.position, scene.start) > 1.0) {
                        staleOnStop = scene.npc
                        results.add("stopping with active task=${scene.record.id} action=${navigation.actionId}; cleanup must follow native server lifecycle")
                        awaitingStop = true
                        progress()
                        event.server.saveEverything(false, true, true)
                        event.server.halt(false)
                    }
                }
            }
            if (!awaitingStop && ticks % 100 == 0) progress()
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) fun stoppedLifecycle(event: ServerStoppedEvent) {
        if (!enabled || failed || !awaitingStop) return
        try {
            check(BehaviorRuntimeService.serverOrNull() == null)
            check(ids.all { BehaviorRuntimeService.diagnostic(it) == null })
            check(BehaviorPlanning.statistics().waiting == 0)
            check(checkNotNull(staleOnStop).stopControl().status == NpcActionStatus.REJECTED)
            staleOnStop = null
            stopScene = null
            Files.writeString(report, "PASS scenarios=19 ticks=$ticks actual_native_lifecycle=true runtime_cleared_after_stop=true\n" + results.joinToString("\n") + "\n")
        } catch (error: Exception) {
            failed = true
            logger.error("Lifecycle post-stop verification failed", error)
            Files.writeString(report, "FAIL after_stop\n${error.stackTraceToString()}\n")
        }
    }

    private fun progress() {
        Files.writeString(report, "RUNNING ticks=$ticks phase=$phase awaitingStop=$awaitingStop\n" + results.joinToString("\n") + "\n")
        logger.info("O4_LIFECYCLE_PROGRESS ticks={} phase={} awaitingStop={}", ticks, phase, awaitingStop)
    }

    private inline fun guarded(server: MinecraftServer, action: () -> Unit) {
        try { action() }
        catch (error: Exception) {
            failed = true
            logger.error("Lifecycle verification failed; preserving the real world", error)
            Files.writeString(report, "FAIL ticks=$ticks phase=$phase\n${error.stackTraceToString()}\n")
            server.saveEverything(false, true, true)
            server.halt(false)
        }
    }
}
