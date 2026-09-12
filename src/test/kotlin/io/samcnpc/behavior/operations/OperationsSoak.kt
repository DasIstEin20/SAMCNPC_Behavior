package io.samcnpc.behavior.operations

import com.google.gson.GsonBuilder
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcPosition
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Mod.EventBusSubscriber(modid=SamcnpcBehavior.MOD_ID)
object OperationsSoak {
    private val enabled=java.lang.Boolean.getBoolean("samcnpc.operationsSoak")
    private val logger=com.mojang.logging.LogUtils.getLogger()
    private val kinds=listOf(OperationKind.WOOD_REPLANT,OperationKind.MINING,OperationKind.FARM,OperationKind.PLANTING,OperationKind.TRANSPORT,OperationKind.FOOD)
    private var scenes=emptyList<OperationScene>()
    private var stump: StumpSoakScene?=null
    private var started=0L
    private var ticks=0
    private var round=0
    private var roundTicks=0
    private var ready=false
    private var done=false
    private var cleanupTicks=0
    private val retired=mutableSetOf<UUID>()
    private val results=mutableListOf<Map<String,Any>>()

    @SubscribeEvent fun started(event: ServerStartedEvent) {
        if(!enabled) return
        guarded(event.server) {
            check(event.server.isDedicatedServer && event.server.playerCount == 0)
            OperationCases.configure(event.server)
            started=System.nanoTime(); beginRound(event.server)
        }
    }
    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if(!enabled || done || started == 0L || event.phase != TickEvent.Phase.END) return
        guarded(event.server) {
            ticks++; roundTicks++
            check(ticks <= 72000 && elapsedSeconds() < 4500) { "Paced soak exceeded its bounded campaign duration" }
            check(roundTicks < 7000) { "Soak round $round stalled: ${scenes.map { TaskService.status(event.server,it.npcId) }}" }
            if(!ready) {
                if(!scenes.all { it.loaded && it.body.onGround() }) { check(roundTicks < 600) { "Soak fixture bodies did not become live" }; return@guarded }
                for(s in scenes) OperationCases.prepare(s)
                ready=true
            }
            for(s in scenes) {
                if(s.checked) continue
                val record=s.record
                check(record.status != TaskStatus.FAILED && record.status != TaskStatus.CANCELLED) { "Soak round=$round ${s.kind}: ${record.report()}" }
                OperationResourceCases.advanceWorldInput(s)
                if(record.status.terminal) { OperationCases.verify(s); s.checked=true }
            }
            checkNotNull(stump).tick()
            if(scenes.all { it.checked } && checkNotNull(stump).complete) finishRound(event.server)
            if(!done && ticks%600 == 0) progress(event.server)
        }
    }
    private fun beginRound(server: MinecraftServer) {
        round++; roundTicks=0; ready=false; cleanupTicks=0
        scenes=kinds.mapIndexed { index,kind -> OperationScene.create(server.overworld(),kind,BlockPos(400+index*64,80,400)) }
        stump=StumpSoakScene(server.overworld(),BlockPos(400,-60,528))
        logger.info("O4_SOAK_ROUND_STARTED round={} activeBodies=7 totalRetired={}",round,retired.size)
    }
    private fun finishRound(server: MinecraftServer) {
        if(++cleanupTicks < 40) return
        for(s in scenes) {
            OperationCases.verify(s)
            val snapshot=s.npc.snapshot()
            val position=NpcPosition(s.origin.x+12.5,s.origin.y+1.0,s.origin.z+0.5)
            val outsider=UUID(0,1)
            val permitted=HarvestWorkClaims.kernel.incidentalCollectionFilter(outsider,outsider,snapshot.dimensionId,position,2.0,snapshot.gameTime)
            check(permitted(position)) { "${s.kind} retained a completed harvest lease" }
        }
        val stumpScene=checkNotNull(stump)
        stumpScene.verify()
        val all=scenes+stumpScene.scene
        for(s in all) {
            // Every physical result above was verified. Empty these isolated fixture containers
            // before the next reset: native ChestBlock.onRemove otherwise spills the old yield.
            for (x in -3..32) for (z in -8..12) for (y in 0..10) {
                val blockEntity = s.level.getBlockEntity(s.pos(x, y, z)) ?: continue
                val container = blockEntity as? net.minecraft.world.Container ?: continue
                container.clearContent()
                blockEntity.setChanged()
            }
            retired.add(s.npcId); s.close()
            // Only after exact per-round assertions: remove optional native leftovers from this isolated fixture.
            for(drop in s.level.getEntitiesOfClass(ItemEntity::class.java,AABB(s.pos(-3,0,-8),s.pos(33,12,13)))) drop.discard()
        }
        val service = CoreNpcApi.service(server)
        for (id in retired) {
            // find also returns a bounded historical identity; only runtime resolves a live body.
            val live = service.find(id)?.let(service::runtime)
            val diagnostic = BehaviorRuntimeService.diagnostic(id)
            check(live == null && diagnostic == null) { "Removed soak NPC $id: liveBody=${live != null} liveBehavior=${diagnostic != null}" }
        }
        val retained=TaskStore.forServer(server).save(CompoundTag()).getList("tasks",Tag.TAG_COMPOUND.toInt())
        check(retained.size == round*kinds.size) { "Expected one bounded terminal report per retired task NPC" }
        check(retained.all { (it as CompoundTag).getString("status") == TaskStatus.COMPLETED.name })
        results.add(linkedMapOf("round" to round,"ticks" to roundTicks,"wallSeconds" to elapsedSeconds(),"physicalTasksCompleted" to kinds.size,
            "stumpDelivered" to 6,"retiredBodies" to retired.size,"retainedTerminalReports" to retained.size,"liveRetiredRuntimes" to 0,
            "heapUsedBytes" to Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),"planningWaiters" to BehaviorPlanning.statistics().waiting))
        progress(server)
        if(ticks >= 36000 && elapsedSeconds() >= 1800.0) {
            done=true
            progress(server)
            Files.writeString(Path.of("operations-soak.txt"),"PASS ticks=$ticks wallSeconds=${elapsedSeconds()} rounds=$round completedTasks=${round*kinds.size} stumpRounds=$round retiredBodies=${retired.size} exact_effects=true live_retired_runtimes=0\n")
            server.saveEverything(false,true,true); server.halt(false)
        } else beginRound(server)
    }
    private fun progress(server: MinecraftServer) {
        val report=linkedMapOf<String,Any>("status" to if(done) "PASS" else "RUNNING","ticks" to ticks,"wallSeconds" to elapsedSeconds(),"round" to round,
            "currentCompleted" to scenes.count { it.checked },"requiredSeconds" to 1800,"requiredTicks" to 36000,
            "retainedReportsAreBoundedHistory" to true,"rounds" to results)
        Files.writeString(Path.of("operations-soak-progress.json"),GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n")
        Files.writeString(Path.of("operations-soak.txt"),"RUNNING ticks=$ticks seconds=${elapsedSeconds()} round=$round completed=${scenes.count { it.checked }}/6 stump=${stump?.complete} players=${server.playerCount}\n")
        logger.info("O4_SOAK_PROGRESS ticks={} seconds={} round={} completed={}/6 stump={}",ticks,elapsedSeconds(),round,scenes.count { it.checked },stump?.complete)
    }
    private fun elapsedSeconds()=(System.nanoTime()-started)/1_000_000_000.0
    private inline fun guarded(server: MinecraftServer,action: () -> Unit) {
        try { action() }
        catch(error: Exception) {
            done=true
            logger.error("Soak failed; stopping immediately with live drop/entity state preserved",error)
            Files.writeString(Path.of("operations-soak.txt"),"FAIL round=$round ticks=$ticks seconds=${elapsedSeconds()}\n${error.stackTraceToString()}\n")
            server.saveEverything(false,true,true); server.halt(false)
        }
    }
}
