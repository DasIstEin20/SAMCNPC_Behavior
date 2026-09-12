package io.samcnpc.behavior.operations

import com.google.gson.GsonBuilder
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.CoreNpcApi
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Mod.EventBusSubscriber(modid=SamcnpcBehavior.MOD_ID)
object ZooHourSmoke {
    private val mode=System.getProperty("samcnpc.zooHour")
    private val logger=com.mojang.logging.LogUtils.getLogger()
    private val gson=GsonBuilder().setPrettyPrinting().create()
    private val clock=ZooHourClock()
    private var measurements: ZooHourMeasurements?=null
    private var scenes=emptyList<ZooHourScene>()
    private val retired=mutableSetOf<UUID>()
    private val results=mutableListOf<Map<String,Any>>()
    private val completedFamilies=mutableSetOf<String>()
    private var started=0L
    private var worldSeed=0L
    private var sourceHash=""
    private var ticks=0
    private var round=0
    private var roundTicks=0
    private var cleanupTicks=0
    private var ready=false
    private var mutated=false
    private var done=false
    private var failed=false
    private val probe get() = mode == "PROBE"

    @SubscribeEvent fun started(event: ServerStartedEvent) {
        if (mode == null) return
        guarded(event.server) {
            check(mode in setOf("FULL","PROBE"))
            check(event.server.isDedicatedServer && event.server.playerCount == 0)
            sourceHash=System.getProperty("samcnpc.zooSourceHash","")
            check(sourceHash.matches(Regex("[0-9a-f]{64}"))) { "supply the frozen source manifest hash" }
            OperationCases.configure(event.server)
            worldSeed=event.server.overworld().seed;measurements=ZooHourMeasurements()
            started=System.nanoTime();beginRound(event.server);report("RUNNING")
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) fun begin(event: TickEvent.ServerTickEvent) {
        if (mode == null || done || failed || started == 0L || event.phase != TickEvent.Phase.START) return
        mutated=false
        checkNotNull(measurements).begin(ready && ticks >= 200 && scenes.any { it.active })
    }
    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (mode == null || done || failed || started == 0L || event.phase != TickEvent.Phase.END) return
        guarded(event.server) {
            ticks++;roundTicks++
            check(ticks <= (if (probe) 9000 else 108000) && elapsed() < (if (probe) 450.0 else 5400.0)) { "Zoo hour exceeded its declared whole-process limit" }
            check(roundTicks < 7000) { "Zoo round $round stalled: ${scenes.map { TaskService.status(event.server,it.scene.npcId) }}" }
            clock.observe(System.nanoTime(),ready && scenes.any { it.active })
            if (!ready) {
                if (!scenes.all { it.scene.loaded && it.scene.body.onGround() }) { check(roundTicks < 600);return@guarded }
                scenes.forEach { it.assign() };ready=true;mutated=true
            }
            for (s in scenes) if (s.tick(ticks)) mutated=true
            if (scenes.all { it.complete } && ++cleanupTicks >= 40) finishRound(event.server)
            if (!done && ticks%600 == 0) { mutated=true;report("RUNNING") }
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) fun measure(event: TickEvent.ServerTickEvent) {
        if (mode == null || failed || started == 0L || event.phase != TickEvent.Phase.END) return
        guarded(event.server) { checkNotNull(measurements).end(mutated || done) }
    }
    private fun beginRound(server: MinecraftServer) {
        round++;roundTicks=0;cleanupTicks=0;ready=false;mutated=true
        check(round*6 <= 4096) { "declared bounded terminal task history would be exceeded" }
        scenes=ZooHourPlan.kinds(round).mapIndexed { index,kind ->
            ZooHourScene(OperationScene.create(server.overworld(),kind,BlockPos(400+index*64,80,400)))
        }
        logger.info("ZOO_HOUR_ROUND_STARTED mode={} round={} families={}",mode,round,scenes.map { it.family })
    }
    private fun finishRound(server: MinecraftServer) {
        mutated=true
        for (s in scenes) {
            OperationCases.verify(s.scene)
            val snapshot=s.scene.npc.snapshot();val at=s.scene.point(12,0);val outsider=UUID(0,1)
            val filter=HarvestWorkClaims.kernel.incidentalCollectionFilter(outsider,outsider,snapshot.dimensionId,at,2.0,snapshot.gameTime)
            check(filter(at)) { "${s.family} retained a completed work reservation" }
        }
        checkNotNull(measurements).retire(scenes)
        val sceneEvidence=scenes.map { it.evidence() }
        for (s in scenes) {
            completedFamilies.add(s.family)
            cleanup(s.scene);retired.add(s.scene.npcId)
        }
        val service=CoreNpcApi.service(server)
        for (id in retired) check(service.find(id)?.let(service::runtime) == null && BehaviorRuntimeService.diagnostic(id) == null) { "retired NPC $id retained a live runtime" }
        val saved=TaskStore.forServer(server).save(CompoundTag())
        val tasks=saved.getList("tasks",10)
        check(tasks.size == round*6 && tasks.all { (it as CompoundTag).getString("status") == TaskStatus.COMPLETED.name })
        val planning=BehaviorPlanning.statistics()
        check(planning.waiting == 0 && planning.peakReserved <= planning.limit) { "planning admission/retention invariant failed: $planning" }
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use { NbtIo.write(saved,it) }
        results.add(linkedMapOf("round" to round,"roundTicks" to roundTicks,"elapsedSeconds" to elapsed(),
            "activeSeconds" to clock.activeSeconds,"activeTicks" to clock.activeTicks,"scenes" to sceneEvidence,
            "retiredBodies" to retired.size,"retainedTerminalReports" to tasks.size,"serializedStoreBytes" to bytes.size(),
            "liveRetiredRuntimes" to 0,"planningWaiters" to planning.waiting,"planningPeakReserved" to planning.peakReserved,
            "planningLimit" to planning.limit,"heapUsedBytes" to Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()))
        ready=false
        report("RUNNING")
        if (clock.reached(probe) && completedFamilies == ZooHourPlan.families) {
            checkNotNull(measurements).verify()
            done=true;report("AWAITING_NATIVE_STOP")
            Files.writeString(Path.of("zoo-hour-samples.json"),gson.toJson(checkNotNull(measurements).raw())+"\n")
            server.saveEverything(false,true,true);server.halt(false)
        } else beginRound(server)
    }
    private fun cleanup(s: OperationScene) {
        // Exact postconditions were checked above. Remove only this isolated fixture's
        // contents before resetting it; otherwise native container removal spills old yield.
        for (x in -3..32) for (z in -8..12) for (y in -2..10) {
            val block=s.level.getBlockEntity(s.pos(x,y,z)) ?: continue
            val container=block as? net.minecraft.world.Container ?: continue
            container.clearContent();block.setChanged()
        }
        s.close()
        for (drop in s.level.getEntitiesOfClass(ItemEntity::class.java,AABB(s.pos(-3,-2,-8),s.pos(33,12,13)))) drop.discard()
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) fun stopped(event: ServerStoppedEvent) {
        if (mode == null || !done || failed) return
        try {
            check(BehaviorRuntimeService.serverOrNull() == null && retired.all { BehaviorRuntimeService.diagnostic(it) == null })
            report("PASS")
            Files.writeString(Path.of("zoo-hour.txt"),"PASS mode=$mode active_seconds=${clock.activeSeconds} active_ticks=${clock.activeTicks} total_ticks=$ticks elapsed_seconds=${elapsed()} rounds=$round roles=6 families=${completedFamilies.size} completed_tasks=${round*6} native_stop=true source_hash=$sourceHash\n")
        } catch (error: Exception) {
            failed=true;logger.error("Zoo hour native stop failed",error);report("FAIL",error.stackTraceToString())
            Files.writeString(Path.of("zoo-hour.txt"),"FAIL after_stop\n${error.stackTraceToString()}")
        }
    }
    private fun report(status: String,failure: String?=null) {
        val data=linkedMapOf("status" to status,"mode" to mode,"sourceHash" to sourceHash,"worldSeed" to worldSeed,
            "activeSeconds" to clock.activeSeconds,"activeTicks" to clock.activeTicks,"excludedLongClockGaps" to clock.excludedLongGaps,
            "requiredActiveSeconds" to if (probe) 60 else 3600,"requiredActiveTicks" to if (probe) 1200 else 72000,
            "elapsedSeconds" to elapsed(),"totalTicks" to ticks,"round" to round,"ready" to ready,
            "completedFamilies" to completedFamilies.sorted(),"rounds" to results,"performance" to measurements?.summary(),
            "liveScenes" to if (ready) scenes.map { it.evidence() } else emptyList<Any>(),"failure" to failure)
        Files.writeString(Path.of("zoo-hour-progress.json"),gson.toJson(data)+"\n")
        if (status != "PASS") Files.writeString(Path.of("zoo-hour.txt"),"$status mode=$mode activeSeconds=${clock.activeSeconds} activeTicks=${clock.activeTicks} totalTicks=$ticks round=$round\n")
        logger.info("ZOO_HOUR_PROGRESS status={} mode={} activeSeconds={} activeTicks={} round={} families={}",status,mode,clock.activeSeconds,clock.activeTicks,round,completedFamilies.size)
    }
    private fun elapsed()=if (started == 0L) 0.0 else (System.nanoTime()-started)/1_000_000_000.0
    private inline fun guarded(server: MinecraftServer,action: ()->Unit) {
        try { action() } catch (error: Exception) {
            failed=true;logger.error("Zoo hour failed; preserving native world and reports",error)
            report("FAIL",error.stackTraceToString())
            Files.writeString(Path.of("zoo-hour.txt"),"FAIL mode=$mode round=$round ticks=$ticks\n${error.stackTraceToString()}")
            server.saveEverything(false,true,true);server.halt(false)
        }
    }
}
