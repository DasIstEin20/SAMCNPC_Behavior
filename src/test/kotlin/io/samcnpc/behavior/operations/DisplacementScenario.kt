package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

internal enum class DisplacementCase(val operation:OperationKind,val combat:Boolean=false) {
    MINER_FALL_AND_ROUTE_CHANGE(OperationKind.MINING),
    FARMER_FALL_AND_ROUTE_CHANGE(OperationKind.FARM),
    LUMBERJACK_FALL_AND_ROUTE_CHANGE(OperationKind.WOOD),
    MINER_SUSPENDED_FALL(OperationKind.MINING,true),
    FARMER_SUSPENDED_FALL(OperationKind.FARM,true),
    LUMBERJACK_SUSPENDED_FALL(OperationKind.WOOD,true),
}

/** A finite external displacement creates a real gravity fall and forces a fresh return route. */
internal class DisplacementScenario(server:MinecraftServer,val kind:DisplacementCase,seed:Long):ZooScenario {
    override val scene=OperationScene.create(server.overworld(),kind.operation,BlockPos(4500+kind.ordinal*64,80,1000))
    private val timeline=ZooIncidents(seed,listOf(ZooIncident(kind.name,"partial_work",mapOf("fallHeight" to "3.2","barrier" to "bedrock return wall"))))
    private var oracle:ZooTaskOracle?=null
    private var ticks=0;private var ready=false;private var quiet=0;private var airborneTicks=0
    private var startFallY=0.0;private var lowestAfterFall=Double.POSITIVE_INFINITY
    private var retained=emptySet<NpcBlockPosition>()
    private var frozenWork:Set<NpcBlockPosition>?=null;private var combatTicks=0
    private val wall=mutableListOf<BlockPos>()
    private var last=emptyMap<String,Any?>()
    override var complete=false
        private set

    override fun tick() {
        if(complete)return
        check(++ticks<6200) { "$kind timed out ${if(ready) scene.record.report() else "loading"}" }
        if(!ready) {
            if(!scene.loaded || !scene.body.onGround())return
            if(kind.combat)scene.give(Items.IRON_SWORD)
            OperationCases.prepare(scene)
            if(kind.combat)check(TaskCombatReactions.configure(scene.server,scene.npcId,TaskReactionPolicy(TaskReactionMode.RETALIATE)).status==NpcActionStatus.SUCCEEDED)
            oracle=ZooTaskOracle(sample());ready=true
        }
        checkNotNull(oracle).observe(sample())
        val record=scene.record
        check(scene.body.isAlive && scene.body.health>=scene.body.maxHealth-3.0F) { "$kind did not survive the bounded incident" }
        OperationResourceCases.advanceWorldInput(scene)
        if(!timeline.complete && work().isNotEmpty()) {
            val before=facts();retained=work().toSet()
            for(y in 1..2) for(z in -2..2) {
                val p=scene.pos(6,y,z);check(scene.level.getBlockState(p).isAir);wall.add(p)
                scene.level.setBlock(p,Blocks.BEDROCK.defaultBlockState(),3)
            }
            startFallY=scene.origin.y+4.2
            scene.body.setPos(scene.origin.x+10.5,startFallY,scene.origin.z-4.5)
            scene.body.deltaMovement=Vec3.ZERO
            if(kind.combat) {
                val enemy=scene.enemy(EntityType.COW,10,-2)
                check(scene.body.hurt(scene.body.damageSources().mobAttack(enemy),0.25F))
            }
            timeline.record(checkNotNull(timeline.pending("partial_work")),ticks,before,facts())
        }
        if(timeline.complete) {
            if(!scene.body.onGround())airborneTicks++
            lowestAfterFall=minOf(lowestAfterFall,scene.body.y)
            check(work().containsAll(retained)) { "$kind forgot physically confirmed work after displacement" }
            check(wall.all { scene.level.getBlockState(it).`is`(Blocks.BEDROCK) }) { "$kind mined an unauthorized return route" }
        }
        if(record.active.definition is AttackTaskDefinition) {
            if(frozenWork==null)frozenWork=work().toSet()
            check(work()==frozenWork && record.frames.size==2) { "$kind changed primary work during combat" }
            combatTicks++
        }
        last=capture()
        if(!record.status.terminal)return
        check(timeline.complete && airborneTicks>=3 && startFallY-lowestAfterFall>=3.0) { "$kind did not exercise a native fall" }
        if(kind.combat)check(combatTicks>0 && record.completedInterruptions==1) { "$kind did not resume its suspended task" }
        OperationCases.verify(scene)
        if(++quiet<40)return
        checkNotNull(oracle).observe(sample(),released=true)
        complete=true;last=capture();scene.close()
    }
    private fun work():Set<NpcBlockPosition> = when(kind.operation) {
        OperationKind.MINING -> scene.record.primary.mining?.selection?.removed?.keys.orEmpty()
        OperationKind.FARM -> scene.record.primary.farming?.planted?.keys.orEmpty()
        OperationKind.WOOD -> scene.record.primary.lumberjack?.observedRemovedBlocks.orEmpty()
        else -> error("unsupported displacement family")
    }
    private fun sample():ZooTaskSample {
        val r=scene.record;val s=scene.npc.snapshot();val p=s.position
        return ZooTaskSample(r.id,r.primary.id,r.primary.remainingTicks,r.frames.size,
            p.x>=scene.origin.x-3 && p.x<=scene.origin.x+33 && p.z>=scene.origin.z-8 && p.z<=scene.origin.z+13 && p.y>=scene.origin.y && p.y<=scene.origin.y+11,
            listOf(s.navigation,s.control,s.blockBreak,s.itemUse,s.rangedAttack).count { it!=null })
    }
    private fun facts()=mapOf("position" to scene.body.position().toString(),"work" to work().toString(),"remaining" to scene.record.primary.remainingTicks.toString())
    private fun capture():Map<String,Any?> = linkedMapOf("case" to kind.name,"status" to if(complete) "PASS" else "RUNNING","npc" to scene.npcId.toString(),"ticks" to ticks,
        "combatTicks" to combatTicks,"airborneTicks" to airborneTicks,"observedFall" to if(lowestAfterFall.isFinite()) startFallY-lowestAfterFall else 0.0,"retainedWork" to retained,"incidents" to timeline.evidence,"task" to scene.record.report(),"facts" to facts())
    override fun evidence()=last.ifEmpty { mapOf("case" to kind.name,"status" to "SETTING_UP") }
}
