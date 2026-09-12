package io.samcnpc.behavior.operations

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Mod.EventBusSubscriber(modid=SamcnpcBehavior.MOD_ID)
object OperationsRestartSmoke {
    private val logger=com.mojang.logging.LogUtils.getLogger()
    private val phase=System.getProperty("samcnpc.operationsRestart")
    private val expectedFile=Path.of("operations-checkpoints.nbt").toFile()
    private val scenes=mutableListOf<OperationScene>()
    private val paused=mutableSetOf<UUID>()
    private val oldActions=mutableSetOf<UUID>()
    private var expected=CompoundTag()
    private var deadCourier: CourierDeathCheckpoint?=null
    private var actor: OperationActor?=null
    private var ticks=0
    private var quietTicks=0
    private var assigned=false
    private var resumed=false
    private var done=false
    private var startedNanos=0L

    @SubscribeEvent fun started(event: ServerStartedEvent) {
        if(phase == null) return
        guarded(event.server) {
            check(event.server.isDedicatedServer && event.server.playerCount == 0)
            OperationCases.configure(event.server)
            startedNanos=System.nanoTime()
            if(phase == "save") {
                check(!expectedFile.exists()) { "Use a fresh operationsSmokeId for another save/load pair" }
                actor=OperationActor(event.server.overworld(),UUID.randomUUID())
                deadCourier=CourierDeathCheckpoint.create(event.server)
                for((index,kind) in OperationKind.entries.withIndex()) {
                    val origin=BlockPos(400+index%5*48,80,400+index/5*48)
                    scenes.add(OperationScene.create(event.server.overworld(),kind,origin))
                }
            } else {
                check(phase == "load")
                expected=NbtIo.readCompressed(expectedFile)
                check(expected.contains("deadCourier"))
                deadCourier=CourierDeathCheckpoint.load(event.server,expected.getCompound("deadCourier"))
                check(expected.getLong("savePid") != ProcessHandle.current().pid()) { "Load must use another server JVM" }
                for(row in expected.getList("scenes",Tag.TAG_COMPOUND.toInt())) scenes.add(OperationScene.load(event.server.overworld(),row as CompoundTag))
                for(id in expected.getList("oldActions",Tag.TAG_STRING.toInt())) oldActions.add(UUID.fromString(id.asString))
                check(scenes.size == OperationKind.entries.size)
            }
        }
    }
    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if(phase == null || done || startedNanos == 0L || event.phase != TickEvent.Phase.END) return
        guarded(event.server) {
            ticks++
            check(ticks <= 7200) { "Restart $phase timed out: ${scenes.map { it.kind to TaskService.status(event.server,it.npcId) }}" }
            if(phase == "save") saveTick(event.server) else loadTick(event.server)
            if(!done && ticks%200 == 0) {
                val message="RUNNING phase=$phase ticks=$ticks paused=${paused.size}/${scenes.size} completed=${scenes.count { it.checked }} wallSeconds=${(System.nanoTime()-startedNanos)/1_000_000_000}"
                Files.writeString(Path.of("operations-$phase.txt"),message+"\n")
                logger.info(message)
            }
        }
    }
    private fun saveTick(server: MinecraftServer) {
        checkNotNull(deadCourier).tickSave()
        if(!assigned) {
            if(!scenes.all { it.loaded && it.body.onGround() }) { check(ticks < 600) { "Checkpoint fixture bodies did not become live" }; return }
            for(scene in scenes) OperationCases.prepare(scene)
            assigned=true
        }
        for(scene in scenes) {
            val record=scene.record
            check(!record.status.terminal) { "${scene.kind} ended before its checkpoint: ${record.report()}" }
            if(scene.npcId in paused) continue
            val snapshot=scene.npc.snapshot()
            oldActions.addAll(listOfNotNull(snapshot.navigation?.actionId,snapshot.blockBreak?.actionId,snapshot.control?.actionId,
                snapshot.itemUse?.actionId,snapshot.rangedAttack?.actionId,snapshot.fishing?.actionId))
            oldActions.addAll(snapshot.recentCompletions.mapNotNull { it.result.actionId })
            if(OperationCases.checkpoint(scene)) {
                OperationAmendments.apply(scene,checkNotNull(actor))
                check(TaskService.pause(server,scene.npcId).status == NpcActionStatus.SUCCEEDED)
                paused.add(scene.npcId)
                logger.info("O4_CHECKPOINT kind={} remaining={} task={}",scene.kind,scene.record.primary.remainingTicks,scene.record.id)
            }
        }
        if(paused.size != scenes.size || !scenes.all { it.body.onGround() } || !checkNotNull(deadCourier).ready) return
        if(++quietTicks < 60) return
        val root=CompoundTag(); val rows=ListTag()
        root.putLong("savePid",ProcessHandle.current().pid()); root.putUUID("actor",checkNotNull(actor).player.uuid)
        for(scene in scenes) {
            scene.requireReleased()
            val row=scene.metadata(); row.put("task",TaskCodec.write(scene.record)); row.put("inventory",scene.inventoryTag()); row.put("world",scene.worldFacts())
            if(scene.kind == OperationKind.MACHINE) row.put("machineStock",OperationMachineCase.stock(scene))
            row.putDouble("x",scene.body.x); row.putDouble("y",scene.body.y); row.putDouble("z",scene.body.z)
            rows.add(row)
        }
        root.put("scenes",rows)
        root.put("deadCourier",checkNotNull(deadCourier).metadata())
        val actions=ListTag(); for(id in oldActions.sorted()) actions.add(StringTag.valueOf(id.toString())); root.put("oldActions",actions)
        check(oldActions.isNotEmpty())
        NbtIo.writeCompressed(root,expectedFile)
        actor?.close(); actor=null
        complete(server,"checkpoints=${scenes.size} all_paused=true actual_world_saved=true old_actions=${oldActions.size}")
    }
    private fun loadTick(server: MinecraftServer) {
        if(!resumed) {
            // Core's persisted activity index must load the real bodies; this driver never loads entity NBT.
            if(scenes.any { it.level.getEntity(it.npcId) == null }) {
                check(ticks < 600) { "Production activity index did not load all checkpoint NPCs" }; return
            }
            if(ticks < 40 || !checkNotNull(deadCourier).loaded()) return
            checkNotNull(deadCourier).verifyLoaded(expected.getCompound("deadCourier"))
            actor=OperationActor(server.overworld(),expected.getUUID("actor"))
            val rows=expected.getList("scenes",Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }.associateBy { it.getUUID("npc") }
            for(scene in scenes) {
                val row=checkNotNull(rows[scene.npcId])
                check(scene.record.status == TaskStatus.PAUSED)
                val actual=TaskCodec.write(scene.record); val wanted=row.getCompound("task")
                check(actual == wanted) { "${scene.kind} durable checkpoint differs: expected=$wanted actual=$actual" }
                check(scene.inventoryTag() == row.getCompound("inventory")) { "${scene.kind} inventory/equipment checkpoint changed" }
                check(scene.worldFacts() == row.getCompound("world")) { "${scene.kind} physical block/container/drop/target checkpoint changed" }
                if(scene.kind == OperationKind.MACHINE) OperationMachineCase.verifyLoaded(scene,row.getCompound("machineStock"))
                check(scene.body.distanceToSqr(row.getDouble("x"),row.getDouble("y"),row.getDouble("z")) < 0.0025)
                scene.requireReleased()
                check(scene.npc.snapshot().recentCompletions.none { it.result.actionId in oldActions }) { "${scene.kind} restored a transient action receipt" }
                OperationAmendments.replay(scene,checkNotNull(actor))
                check(TaskService.resume(server,scene.npcId).status == NpcActionStatus.SUCCEEDED)
            }
            actor?.close(); actor=null; resumed=true
        }
        for(scene in scenes) {
            if(scene.checked) continue
            val record=scene.record
            check(record.status != TaskStatus.FAILED && record.status != TaskStatus.CANCELLED) { "${scene.kind} after restart: ${record.report()}" }
            OperationResourceCases.advanceWorldInput(scene)
            check(scene.npc.snapshot().recentCompletions.none { it.result.actionId in oldActions }) { "${scene.kind} old action was replayed" }
            if(record.status.terminal) {
                OperationCases.verify(scene); scene.checked=true
                logger.info("O4_RESTART_COMPLETED kind={} remaining={} task={}",scene.kind,record.primary.remainingTicks,record.id)
            }
        }
        if(scenes.all { it.checked }) {
            // A quiet tail also detects stale resumed actions mutating completed results.
            if(++quietTicks < 40) return
            for(scene in scenes) OperationCases.verify(scene)
            checkNotNull(deadCourier).verifyLoaded(expected.getCompound("deadCourier"))
            complete(server,"checkpoints=${scenes.size} exact_task_inventory_world=true receipts_replayed_without_effect=true all_completed=true stale_actions=0")
        }
    }
    private fun complete(server: MinecraftServer,detail: String) {
        done=true; deadCourier?.release(); server.saveEverything(false,true,true)
        Files.writeString(Path.of("operations-$phase.txt"),"PASS phase=$phase dedicated=true pid=${ProcessHandle.current().pid()} ticks=$ticks courier_death_checkpoint=true $detail\n")
        server.halt(false)
    }
    private inline fun guarded(server: MinecraftServer,action: () -> Unit) {
        try { action() }
        catch(error: Exception) {
            done=true
            logger.error("Operations restart {} failed; preserving the actual world",phase,error)
            Files.writeString(Path.of("operations-$phase.txt"),"FAIL phase=$phase ticks=$ticks\n${error.stackTraceToString()}\n")
            server.saveEverything(false,true,true); server.halt(false)
        }
    }
}
