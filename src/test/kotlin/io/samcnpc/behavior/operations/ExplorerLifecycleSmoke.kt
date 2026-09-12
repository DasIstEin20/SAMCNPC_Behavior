package io.samcnpc.behavior.operations

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Actual distant death, native respawn and stale-handle checks with separate KEEP/DROP JVMs. */
@Mod.EventBusSubscriber(modid=SamcnpcBehavior.MOD_ID)
object ExplorerLifecycleSmoke {
    private val mode=System.getProperty("samcnpc.explorerLifecycle")
    private val logger=com.mojang.logging.LogUtils.getLogger()
    private var scene: OperationScene?=null
    private var originalBody: LivingEntity?=null
    private var oldFacade: NpcFacade?=null
    private var oldTask: TaskRecord?=null
    private var deathRecord: CompoundTag?=null
    private var oldAction: UUID?=null
    private var inspectionChunk: ChunkPos?=null
    private var deathPosition: NpcPosition?=null
    private val previousPacks get() = if (mode == "KEEP") listOf("samcnpc:idle_look") else emptyList()
    private var phase=0
    private var ticks=0
    private var quiet=0
    private var done=false
    private var failed=false

    @SubscribeEvent fun started(event: ServerStartedEvent) {
        if (mode == null) return
        guarded(event.server) {
            check(mode in setOf("KEEP","DROP") && System.getProperty("samcnpc.deathPolicyFixture") == mode)
            check(event.server.isDedicatedServer && event.server.playerCount == 0)
            OperationCases.configure(event.server)
            val level=event.server.overworld()
            val origin=BlockPos(1600,80,1600)
            for (x in -4..80) for (z in -20..20) for (y in 0..8)
                level.setBlock(origin.offset(x,y,z),(if (y == 0) Blocks.STONE else Blocks.AIR).defaultBlockState(),3)
            val actor=OperationActor(level,UUID.randomUUID())
            try {
                val request=NpcSummonRequest(actor.player.uuid,"ExplorerLifecycle",level.dimension().location().toString(),
                    NpcPosition(origin.x+0.5,origin.y+1.0,origin.z+0.5),-90.0F)
                val summoned=CoreNpcApi.service(event.server).summon(request)
                check(summoned.result.status == NpcActionStatus.SUCCEEDED) { summoned.result.detail }
                val s=OperationScene(level,OperationKind.EXPLORER_LEG,origin,checkNotNull(summoned.handle).npcUuid)
                scene=s;originalBody=s.body
                check(s.npc.snapshot().summonerUuid == actor.player.uuid)
            } finally { actor.close() }
        }
    }
    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (mode == null || done || failed || event.phase != TickEvent.Phase.END) return
        guarded(event.server) {
            val s=checkNotNull(scene)
            check(++ticks < 2200) { "Explorer lifecycle timed out: phase=$phase status=${TaskService.status(event.server,s.npcId)}" }
            when (phase) {
                0 -> {
                    if (!s.loaded || !s.body.onGround()) return@guarded
                    // The real command writes Core's persistent respawn origin. The test never recreates a body.
                    val cmd="samcnpc setspawnpoint ${s.npcId} ${s.start.x} ${s.start.y} ${s.start.z}"
                    check(s.server.commands.performPrefixedCommand(s.server.createCommandSourceStack(),cmd) == 1)
                    s.give(Items.DIAMOND,7)
                    check(BehaviorRuntimeService.assignPacks(s.server,s.npcId,previousPacks).status == NpcActionStatus.SUCCEEDED)
                    s.assign(ExplorerTaskDefinition(s.npc.snapshot().dimensionId,s.start,radius=64,maxCells=32,chunkBudget=144,budget=TaskBudget(3600)))
                    oldTask=s.record;phase=1
                }
                1 -> {
                    check(!s.record.status.terminal) { s.record.report() }
                    val snapshot=s.npc.snapshot()
                    val navigation=snapshot.navigation
                    if (TaskNavigator.distanceSquared(snapshot.position,s.start) < 40.0*40.0 || navigation == null) return@guarded
                    check(checkNotNull(s.record.primary.explorer).nodes.size >= 10)
                    oldFacade=s.npc;oldAction=navigation.actionId;deathPosition=snapshot.position
                    // Inspection keeps only the known death chunk loaded. It does not move/restore NPCs
                    // or claim that production retains arbitrary death sites after respawn.
                    val chunk=ChunkPos(BlockPos.containing(s.body.position()));inspectionChunk=chunk
                    s.level.setChunkForced(chunk.x,chunk.z,true)
                    check(s.body.hurt(s.body.damageSources().genericKill(),1000.0F));phase=2
                }
                2 -> {
                    val body=s.level.getEntity(s.npcId) as? LivingEntity
                    if (body == null || body === originalBody || !body.isAlive || !body.onGround()) return@guarded
                    check(s.record === oldTask && s.record.status == TaskStatus.CANCELLED && s.record.reason == TaskReason.NPC_REMOVED)
                    s.requireReturned();s.requireReleased()
                    check(BehaviorRuntimeService.assignedPacks(s.server,s.npcId) == previousPacks)
                    val stale=checkNotNull(oldFacade).stopControl()
                    check(stale.status == NpcActionStatus.REJECTED && stale.code == NpcActionCode.NOT_FOUND)
                    check(s.npc.snapshot().recentCompletions.none { it.result.actionId == oldAction })
                    check(TaskService.resume(s.server,s.npcId).status == NpcActionStatus.REJECTED)
                    deathRecord=TaskCodec.write(s.record);check(TaskCodec.read(checkNotNull(deathRecord)).report() == s.record.report())
                    verifyItems(s)
                    // Reproduce a pre-fix persisted terminal assignment without changing its report.
                    check(BehaviorRuntimeService.assignPacks(s.server,s.npcId,listOf(TaskService.EXPLORER_PACK_ID)).status == NpcActionStatus.SUCCEEDED)
                    phase=3
                }
                3 -> {
                    check(TaskCodec.write(s.record) == deathRecord);s.requireReleased();s.requireReturned();verifyItems(s)
                    check(BehaviorRuntimeService.assignedPacks(s.server,s.npcId) == previousPacks)
                    if (++quiet < 70) return@guarded
                    check(BehaviorRuntimeService.assignPacks(s.server,s.npcId,emptyList()).status == NpcActionStatus.SUCCEEDED)
                    val fresh=ExplorerTaskDefinition(s.npc.snapshot().dimensionId,s.start,radius=16,maxCells=3,budget=TaskBudget(800))
                    s.assign(fresh);check(s.record.id != checkNotNull(oldTask).id);phase=4
                }
                4 -> {
                    if (!s.record.status.terminal) return@guarded
                    s.requireCompleted();s.requireReturned();verifyItems(s)
                    check(TaskCodec.write(checkNotNull(oldTask)) == deathRecord)
                    s.close();inspectionChunk?.let { s.level.setChunkForced(it.x,it.z,false) };inspectionChunk=null
                    done=true
                    Files.writeString(Path.of("explorer-lifecycle.txt"),"AWAITING_STOP mode=$mode ticks=$ticks\n")
                    event.server.saveEverything(false,true,true);event.server.halt(false)
                }
            }
            if (ticks%100 == 0) logger.info("EXPLORER_LIFECYCLE mode={} ticks={} phase={}",mode,ticks,phase)
        }
    }
    private fun verifyItems(s: OperationScene) {
        val drop=s.level.getEntitiesOfClass(ItemEntity::class.java,AABB(s.pos(-4,0,-20),s.pos(81,10,21)))
            .sumOf { if (it.item.`is`(Items.DIAMOND)) it.item.count else 0 }
        val carried=s.carried("minecraft:diamond")
        check(carried+drop == 7) { "Death items duplicated or lost: carried=$carried dropped=$drop" }
        check(if (mode == "KEEP") carried == 7 && drop == 0 else carried == 0 && drop == 7)
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) fun stopped(event: ServerStoppedEvent) {
        if (mode == null || failed || !done) return
        try {
            val s=checkNotNull(scene)
            check(BehaviorRuntimeService.serverOrNull() == null && BehaviorRuntimeService.diagnostic(s.npcId) == null)
            Files.writeString(Path.of("explorer-lifecycle.txt"),"PASS mode=$mode dedicated=true ticks=$ticks distant_death_blocks=${kotlin.math.sqrt(TaskNavigator.distanceSquared(checkNotNull(deathPosition),s.start))} stable_uuid=true new_body=true native_respawn=true exact_items=7 cancelled_old_task=true prior_packs_restored=true legacy_terminal_assignment_reconciled=true stale_action_rejected=true fresh_expedition=true native_stop=true\n")
        } catch (error: Exception) {
            failed=true;logger.error("Explorer lifecycle stop failure",error)
            Files.writeString(Path.of("explorer-lifecycle.txt"),"FAIL after_stop\n${error.stackTraceToString()}")
        }
    }
    private inline fun guarded(server: MinecraftServer,action: ()->Unit) {
        try { action() } catch (error: Exception) {
            failed=true;logger.error("Explorer lifecycle failed mode={} phase={} ticks={}",mode,phase,ticks,error)
            Files.writeString(Path.of("explorer-lifecycle.txt"),"FAIL mode=$mode phase=$phase ticks=$ticks\n${error.stackTraceToString()}")
            server.saveEverything(false,true,true);server.halt(false)
        }
    }
}
