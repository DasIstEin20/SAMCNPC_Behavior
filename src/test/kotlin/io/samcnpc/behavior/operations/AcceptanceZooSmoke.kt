package io.samcnpc.behavior.operations

import com.google.gson.GsonBuilder
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import net.minecraft.server.MinecraftServer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path

/** Ordinary server acceptance fixtures. Production packs still select and execute every NPC action. */
@Mod.EventBusSubscriber(modid = SamcnpcBehavior.MOD_ID)
object AcceptanceZooSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.acceptanceZoo")
    private val logger = com.mojang.logging.LogUtils.getLogger()
    private var cases = emptyList<ZooScenario>()
    private var ticks = 0
    private var finished = false
    private var failed = false
    private var profile = "z1"
    private var seed = 0L
    private var started = 0L

    @SubscribeEvent fun started(event: ServerStartedEvent) {
        if (!enabled) return
        guarded(event.server) {
            check(event.server.isDedicatedServer && event.server.playerCount == 0)
            seed = System.getProperty("samcnpc.zooSeed", "20260912").toLong()
            OperationCases.configure(event.server)
            started = System.nanoTime()
            profile = System.getProperty("samcnpc.zooProfile", "z1")
            check(profile in setOf("z1", "terrain"))
            cases = if (profile == "terrain") TerrainMiningCase.entries.map { TerrainMiningScenario(event.server, it, seed) } +
                DisplacementCase.entries.map { DisplacementScenario(event.server, it, seed) } +
                TerrainFarmCase.entries.map { TerrainFarmScenario(event.server, it, seed) } + listOf(SharedTerrainScenario(event.server,seed))
            else ZooWorldCase.entries.map { ZooWorldScenario(event.server, it, seed) }
            report("RUNNING")
        }
    }

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (!enabled || finished || failed || started == 0L || event.phase != TickEvent.Phase.END) return
        guarded(event.server) {
            check(++ticks < 6500) { "Zoo campaign exceeded bounded duration" }
            for (scenario in cases) scenario.tick()
            if (ticks % 100 == 0) report("RUNNING")
            if (cases.all { it.complete }) {
                finished = true
                report("AWAITING_NATIVE_STOP")
                event.server.saveEverything(false, true, true)
                event.server.halt(false)
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) fun stopped(event: ServerStoppedEvent) {
        if (!enabled || failed || !finished) return
        try {
            check(BehaviorRuntimeService.serverOrNull() == null)
            check(cases.all { BehaviorRuntimeService.diagnostic(it.scene.npcId) == null })
            report("PASS")
            Files.writeString(Path.of("acceptance-zoo.txt"), "PASS cases=${cases.size} ticks=$ticks seed=$seed native_stop=true\n")
        } catch (error: Exception) {
            failed = true
            logger.error("Zoo stop oracle failed", error)
            report("FAIL", error.stackTraceToString())
            Files.writeString(Path.of("acceptance-zoo.txt"), "FAIL after_stop\n${error.stackTraceToString()}")
        }
    }

    private fun report(status: String, failure: String? = null) {
        val data = linkedMapOf("status" to status, "seed" to seed, "ticks" to ticks,
            "wallSeconds" to (System.nanoTime() - started) / 1_000_000_000.0,
            "sourceHash" to System.getProperty("samcnpc.zooSourceHash", "not_supplied"),
            "profile" to profile, "failure" to failure,
            "cases" to cases.map { it.evidence() })
        Files.writeString(Path.of("acceptance-zoo.json"), GsonBuilder().setPrettyPrinting().create().toJson(data) + "\n")
        logger.info("ZOO_PROGRESS status={} ticks={} complete={}/{}", status, ticks, cases.count { it.complete }, cases.size)
    }

    private inline fun guarded(server: MinecraftServer, action: () -> Unit) {
        try { action() }
        catch (error: Exception) {
            failed = true
            logger.error("Acceptance Zoo failed; preserving native world and trace", error)
            report("FAIL", error.stackTraceToString())
            Files.writeString(Path.of("acceptance-zoo.txt"), "FAIL ticks=$ticks\n${error.stackTraceToString()}")
            server.saveEverything(false, true, true)
            server.halt(false)
        }
    }
}
