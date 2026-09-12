package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

internal enum class TerrainMiningCase { WATER_ARRIVES, LAVA_ARRIVES, SAND_ARRIVES, GRAVEL_ARRIVES, DARKNESS, DESCENT, FULL_RETURN }

/** Mutate a selected target's surroundings, never the task's decisions or action results. */
internal class TerrainMiningScenario(server: MinecraftServer, val kind: TerrainMiningCase, seed: Long) : ZooScenario {
    override val scene = OperationScene.create(server.overworld(), OperationKind.MINING, BlockPos(3900 + kind.ordinal * 64, 80, 1000))
    private val hazardous = kind.ordinal <= TerrainMiningCase.GRAVEL_ARRIVES.ordinal
    private val initialRaw = if (kind == TerrainMiningCase.FULL_RETURN) 60 else 5
    private val amount = when(kind) { TerrainMiningCase.FULL_RETURN -> 6; TerrainMiningCase.DESCENT -> 3; else -> 2 }
    private val ore = if(kind == TerrainMiningCase.DESCENT) listOf(scene.block(12,-1,0),scene.block(13,-2,0),scene.block(14,-2,0))
        else (12 until 12+amount).map { scene.block(it,1,0) }
    private val supports = linkedSetOf<BlockPos>()
    private val timeline = ZooIncidents(seed,listOf(ZooIncident(kind.name,"working",mapOf("quantity" to amount.toString()))))
    private var ticks=0; private var ready=false; private var quiet=0; private var oracle:ZooTaskOracle?=null
    private var minY=Double.POSITIVE_INFINITY; private var intermediateDelivery=false; private var observedLight:Int?=null
    private var last=emptyMap<String,Any?>()
    override var complete=false
        private set

    override fun tick() {
        if(complete) return
        check(++ticks < 6200) { "$kind timed out ${if(ready) scene.record.report() else "loading"}" }
        if(!ready) {
            if(!scene.loaded || !scene.body.onGround()) return
            prepare();ready=true;oracle=ZooTaskOracle(sample())
        }
        val record=scene.record;val state=checkNotNull(record.primary.mining)
        check(scene.body.isAlive && scene.body.health == scene.body.maxHealth) { "$kind injured itself" }
        checkNotNull(oracle).observe(sample())
        minY=minOf(minY,scene.body.y)
        val current=state.target
        if(hazardous && !timeline.complete && state.selection.removed.size==1 && state.phase==MiningPhase.WORK && current?.position==ore[1]) {
            val before=facts(); val at=ore[1]
            val hazard=when(kind) { TerrainMiningCase.WATER_ARRIVES -> Blocks.WATER; TerrainMiningCase.LAVA_ARRIVES -> Blocks.LAVA; TerrainMiningCase.SAND_ARRIVES -> Blocks.SAND; else -> Blocks.GRAVEL }
            val pos=if(kind==TerrainMiningCase.WATER_ARRIVES || kind==TerrainMiningCase.LAVA_ARRIVES) BlockPos(at.x,at.y,at.z+1) else BlockPos(at.x,at.y+1,at.z)
            scene.level.setBlock(pos,hazard.defaultBlockState(),3)
            timeline.record(checkNotNull(timeline.pending("working")),ticks,before,facts())
        } else if(!hazardous && !timeline.complete && state.phase==MiningPhase.WORK && current!=null) {
            observedLight=scene.npc.worldView().observeBlockDetails(current.position)?.environment?.light
            if(kind==TerrainMiningCase.DARKNESS) check(observedLight==0) { "Darkness fixture has light=$observedLight" }
            timeline.record(checkNotNull(timeline.pending("working")),ticks,facts(),facts())
        }
        if(state.delivered(record.primary.definition as MiningTaskDefinition)>0 && state.selection.removed.size<amount) intermediateDelivery=true
        last=capture()
        if(!record.status.terminal) return
        check(timeline.complete)
        val removed=if(hazardous) 1 else amount
        check(state.selection.removed.size==removed && state.delivered(record.primary.definition as MiningTaskDefinition)==removed) { "$kind ${record.report()}" }
        check(scene.count(0,7,Items.RAW_IRON)==removed && scene.carried("minecraft:raw_iron")==initialRaw)
        check(ore.count { scene.level.getBlockState(BlockPos(it.x,it.y,it.z)).isAir }==removed)
        check(state.resources.physical.entries.values.all { it.valid() } && !state.resources.physical.uncertain)
        val drops=scene.level.getEntitiesOfClass(ItemEntity::class.java,AABB(scene.pos(-3,-6,-8),scene.pos(33,12,13))).sumOf { if(it.item.`is`(Items.RAW_IRON)) it.item.count else 0 }
        ZooResourceOracle.conserve((initialRaw+amount).toLong(),(scene.carried("minecraft:raw_iron")+scene.count(0,7,Items.RAW_IRON)+amount-removed+drops).toLong())
        check(supports.all { scene.level.getBlockState(it).`is`(Blocks.STONE) }) { "mining destroyed the access/return support" }
        if(hazardous) {
            check(record.status==TaskStatus.FAILED && record.reason==TaskReason.WORK_FAILED && record.primary.remainingTicks>0)
            check(state.stop==if(kind==TerrainMiningCase.WATER_ARRIVES || kind==TerrainMiningCase.LAVA_ARRIVES) MiningProblem.FLUID else MiningProblem.FALLING_BLOCK)
        } else scene.requireCompleted()
        if(kind==TerrainMiningCase.DESCENT) check(minY < scene.origin.y-0.9) { "body never descended to the lower work" }
        if(kind==TerrainMiningCase.FULL_RETURN) check(intermediateDelivery && scene.carried("minecraft:stone")==34*64)
        scene.requireReturned();scene.requireReleased()
        if(++quiet<40) return
        checkNotNull(oracle).observe(sample(),released=true)
        complete=true;last=capture();scene.close()
    }

    private fun prepare() {
        scene.makeChest(0,7);scene.give(Items.IRON_PICKAXE);scene.give(Items.RAW_IRON,initialRaw)
        if(kind==TerrainMiningCase.FULL_RETURN) scene.give(Items.STONE,34*64)
        if(kind==TerrainMiningCase.DESCENT) {
            for(x in 8..19) for(z in -2..2) {
                val floor=-(x-10).coerceIn(0,3)
                for(y in -5..1) scene.level.setBlock(scene.pos(x,y,z),(if(y<=floor) Blocks.STONE else Blocks.AIR).defaultBlockState(),3)
                supports.add(scene.pos(x,floor,z))
            }
        }
        if(kind==TerrainMiningCase.DARKNESS) {
            for(x in -3..32) for(z in -8..12) scene.level.setBlock(scene.pos(x,4,z),Blocks.STONE.defaultBlockState(),3)
            for(y in 1..3) {
                for(x in -3..32) for(z in listOf(-8,12)) scene.level.setBlock(scene.pos(x,y,z),Blocks.STONE.defaultBlockState(),3)
                for(z in -8..12) for(x in listOf(-3,32)) scene.level.setBlock(scene.pos(x,y,z),Blocks.STONE.defaultBlockState(),3)
            }
        }
        if(kind==TerrainMiningCase.WATER_ARRIVES || kind==TerrainMiningCase.LAVA_ARRIVES) {
            for(p in listOf(scene.pos(12,1,1),scene.pos(14,1,1),scene.pos(13,1,2),scene.pos(13,2,1))) scene.level.setBlock(p,Blocks.GLASS.defaultBlockState(),3)
        }
        for(p in ore) scene.level.setBlock(BlockPos(p.x,p.y,p.z),Blocks.IRON_ORE.defaultBlockState(),3)
        val area=WorkArea(WorkBox(NpcBlockPosition(ore.minOf { it.x },ore.minOf { it.y },ore.minOf { it.z }),NpcBlockPosition(ore.maxOf { it.x },ore.maxOf { it.y },ore.maxOf { it.z })))
        scene.assign(MiningTaskDefinition(scene.npc.snapshot().dimensionId,MiningWorkOrder(area,MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:iron_ore"))),
            WorkResourceIds(listOf("minecraft:raw_iron")),scene.choices(0,7),amount,MiningCounting.DELIVERED_ITEMS,scene.start,returnTo=scene.start,budget=TaskBudget(6000)))
    }
    private fun sample():ZooTaskSample {
        val r=scene.record;val s=scene.npc.snapshot();val d=r.primary.definition as MiningTaskDefinition
        return ZooTaskSample(r.id,r.primary.id,r.primary.remainingTicks,r.frames.size,d.contains(s.position),listOf(s.navigation,s.control,s.blockBreak,s.itemUse,s.rangedAttack).count { it!=null })
    }
    private fun facts()=mapOf("position" to scene.body.position().toString(),"removed" to scene.record.primary.mining?.selection?.removed.toString(),"remaining" to scene.record.primary.remainingTicks.toString())
    private fun capture():Map<String,Any?> = linkedMapOf("case" to kind.name,"status" to if(complete) "PASS" else "RUNNING","npc" to scene.npcId.toString(),"ticks" to ticks,"minY" to minY,"light" to observedLight,"intermediateDelivery" to intermediateDelivery,"incidents" to timeline.evidence,"task" to scene.record.report(),"facts" to facts())
    override fun evidence()=last.ifEmpty { mapOf("case" to kind.name,"status" to "SETTING_UP") }
}
