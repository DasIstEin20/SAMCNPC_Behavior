package io.samcnpc.behavior.operations

import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import kotlin.math.abs

/** Exact six-log, persistent-canopy and bounded-floor regression geometry from the old 5/6 failure. */
internal class StumpSoakScene(level: ServerLevel,origin: BlockPos) {
    val scene=OperationScene.create(level,OperationKind.WOOD,origin,1.5,3.5,0.0F)
    private val trunks=(1..6).map { scene.pos(8,it,3) }
    private val supports=mutableSetOf<NpcBlockPosition>()
    private var previous: LumberjackDemoPhase?=null
    private var started=false
    private var elapsed=0
    private var landings=0
    private var quiet=0
    var complete=false
        private set
    init {
        for(x in -3..32) for(z in -8..12) if(x !in 0..11 || z !in 0..6)
            level.setBlock(scene.pos(x,0,z),Blocks.AIR.defaultBlockState(),3)
        for(at in trunks) level.setBlock(at,Blocks.OAK_LOG.defaultBlockState(),3)
        val leaves=Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true)
        for(x in 6..10) for(z in 1..5) for(y in 4..6) if(x != 8 || z != 3) level.setBlock(scene.pos(x,y,z),leaves,3)
        val chest=scene.makeChest(1,5)
        chest.setItem(0,ItemStack(Items.IRON_AXE)); chest.setItem(1,ItemStack(Items.IRON_SHOVEL)); chest.setItem(2,ItemStack(Items.DIRT,16)); chest.setChanged()
    }
    fun tick() {
        if(complete) return
        check(++elapsed < 2000) { "Paced stump timed out: ${LumberjackService.status(scene.server,scene.npcId)}" }
        if(!started) {
            if(!scene.loaded || !scene.body.onGround()) return
            check(LumberjackService.start(scene.server,scene.npc,scene.npc.worldView()).status == NpcActionStatus.SUCCEEDED)
            started=true
        }
        val snapshot=scene.npc.snapshot()
        val job=LumberjackDemoStore.forServer(scene.server).jobFor(scene.npcId)
        job?.pillarSession?.placedPositions?.let(supports::addAll)
        val base=scene.pos(8,1,3)
        if(previous == LumberjackDemoPhase.CLIMB_TRUNK && job?.phase == LumberjackDemoPhase.BREAK_LOG) {
            check(snapshot.onGround && abs(snapshot.position.y-base.y-1.0) <= 0.05 && abs(snapshot.position.x-base.x-0.5) <= 0.75 && abs(snapshot.position.z-base.z-0.5) <= 0.75)
            landings++
        }
        previous=job?.phase
        if(job != null) {
            val active=job.pillarSession?.placedPositions.orEmpty()
            val upperGone=trunks.drop(1).all { scene.level.getBlockState(it).isAir || NpcBlockPosition(it.x,it.y,it.z) in active }
            check(!(upperGone && active.isNotEmpty() && job.phase == LumberjackDemoPhase.TRAVEL_TO_LOG && job.targetPosition == scene.block(8,1,3))) { "Stump work abandoned its supports" }
            return
        }
        verify()
        if(++quiet >= 40) complete=true
    }
    fun verify() {
        check(started && landings > 0 && supports.isNotEmpty()) { "Paced stump did not exercise the physical scaffold/landing path" }
        check(trunks.all { scene.level.getBlockState(it).isAir })
        check(supports.all { scene.level.getBlockState(BlockPos(it.x,it.y,it.z)).isAir }) { "Paced stump left a temporary support" }
        check(scene.count(1,5,Items.OAK_LOG) == 6) { "Paced stump expected 6 logs, deposited ${scene.count(1,5,Items.OAK_LOG)}; preserving remaining live drops" }
        check(scene.carried("minecraft:oak_log") == 0)
        scene.requireReleased()
        check(BehaviorRuntimeService.assignedPacks(scene.server,scene.npcId).isEmpty())
    }
}
