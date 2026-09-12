package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.FarmBlock

/** Three consumers share output pressure while keeping different work and stock contracts. */
internal class SharedTerrainScenario(server:MinecraftServer,seed:Long):ZooScenario {
    private val origin=BlockPos(5800,80,1000)
    override val scene=OperationScene.create(server.overworld(),OperationKind.MINING,origin)
    private val farmer=OperationScene.create(server.overworld(),OperationKind.FARM,origin,spawnZ=4.5)
    private val wood=OperationScene.create(server.overworld(),OperationKind.WOOD,origin,spawnZ=-4.5)
    private val workers=listOf(scene,farmer,wood)
    private val oracles=mutableMapOf<java.util.UUID,ZooTaskOracle>()
    private val timeline=ZooIncidents(seed,listOf(ZooIncident("OPEN_SHARED_OUTPUT","storage_full",mapOf("movedStoneStacks" to "3","workers" to "miner,farmer,lumberjack"))))
    private var ready=false;private var ticks=0;private var quiet=0;private var pressureAt:Int?=null
    private var partialMiningDelivery=false
    private var last=emptyMap<String,Any?>()
    override var complete=false
        private set

    override fun tick() {
        if(complete)return
        check(++ticks<6200) { "shared workers timed out ${if(ready) facts() else "loading"}" }
        if(!ready) {
            if(workers.any { !it.loaded || !it.body.onGround() })return
            prepare();for(worker in workers)oracles[worker.npcId]=ZooTaskOracle(sample(worker));ready=true
        }
        for(worker in workers) {
            checkNotNull(oracles[worker.npcId]).observe(sample(worker))
            check(worker.body.isAlive && worker.record.status!=TaskStatus.FAILED && worker.record.status!=TaskStatus.CANCELLED) { "shared ${worker.kind}: ${worker.record.report()}" }
        }
        if(pressureAt==null && workers.any { it.record.reason==TaskReason.STORAGE_FULL })pressureAt=ticks
        val blockedAt=pressureAt
        if(!timeline.complete && blockedAt!=null && ticks-blockedAt>=12) {
            val before=facts();val output=scene.chest(0,7);val external=scene.chest(3,7)
            check(scene.count(0,7,Items.STONE)==27*64 && scene.count(3,7,Items.STONE)==0)
            for(slot in 0..2) {
                val moved=output.removeItemNoUpdate(slot);check(moved.`is`(Items.STONE) && moved.count==64)
                external.setItem(slot,moved)
            }
            output.setChanged();external.setChanged()
            timeline.record(checkNotNull(timeline.pending("storage_full")),ticks,before,facts())
        }
        val mining=checkNotNull(scene.record.primary.mining)
        if(mining.delivered(scene.record.primary.definition as MiningTaskDefinition)>0 && mining.selection.removed.size<3)partialMiningDelivery=true
        last=capture()
        if(workers.any { !it.record.status.terminal })return
        check(timeline.complete && partialMiningDelivery)
        for(worker in workers)worker.requireCompleted()
        scene.requireReturned();farmer.requireReturned()
        check(scene.count(0,7,Items.RAW_IRON)==3 && scene.count(0,7,Items.WHEAT)==3 && scene.count(0,7,Items.OAK_LOG)==3)
        check(scene.count(0,7,Items.STONE)==24*64 && scene.count(3,7,Items.STONE)==3*64)
        check(scene.carried("minecraft:raw_iron")==62 && scene.carried("minecraft:stone")==34*64)
        check(farmer.carried("minecraft:wheat")==5 && farmer.carried("minecraft:wheat_seeds")>=1)
        check(wood.carried("minecraft:oak_log")==5 && wood.carried("minecraft:coarse_dirt")==8)
        val farm=checkNotNull(farmer.record.primary.farming)
        check(farm.harvested.size==3 && farm.replanted==farm.harvested && farm.planted.values.sum()==3)
        check(mining.selection.removed.size==3)
        check(checkNotNull(wood.record.primary.lumberjack).observedRemovedBlocks.size==3)
        for(x in 10..12)check(scene.level.getBlockState(scene.pos(x,1,0)).isAir)
        for(x in 12..14) {
            val crop=scene.level.getBlockState(scene.pos(x,1,-3))
            check(crop.`is`(Blocks.WHEAT) && crop.getValue(CropBlock.AGE)==0)
        }
        for(y in 1..3)check(scene.level.getBlockState(scene.pos(14,y,3)).isAir)
        if(++quiet<40)return
        for(worker in workers)checkNotNull(oracles[worker.npcId]).observe(sample(worker),released=true)
        complete=true;last=capture()
        for(worker in workers)worker.close()
    }
    private fun prepare() {
        val dim=scene.npc.snapshot().dimensionId;val budget=TaskBudget(6000)
        val output=scene.makeChest(0,7);scene.makeChest(3,7)
        for(slot in 0 until output.containerSize)output.setItem(slot,ItemStack(Items.STONE,64));output.setChanged()
        scene.give(Items.IRON_PICKAXE);scene.give(Items.RAW_IRON,62);scene.give(Items.STONE,34*64)
        for(x in 10..12)scene.level.setBlock(scene.pos(x,1,0),Blocks.IRON_ORE.defaultBlockState(),3)
        scene.assign(MiningTaskDefinition(dim,MiningWorkOrder(scene.area(10,12),MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:iron_ore"))),
            WorkResourceIds(listOf("minecraft:raw_iron")),scene.choices(0,7),3,MiningCounting.DELIVERED_ITEMS,scene.start,returnTo=scene.start,budget=budget))
        farmer.give(Items.IRON_HOE);farmer.give(Items.WHEAT,5);farmer.give(Items.WHEAT_SEEDS,5)
        for(x in 12..14) {
            scene.level.setBlock(scene.pos(x,0,-3),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7),3)
            scene.level.setBlock(scene.pos(x,1,-3),(Blocks.WHEAT as CropBlock).getStateForAge(7),3)
        }
        scene.level.setBlock(scene.pos(13,-1,-5),Blocks.STONE.defaultBlockState(),3)
        scene.level.setBlock(scene.pos(13,0,-5),Blocks.WATER.defaultBlockState(),3)
        farmer.assign(FarmTaskDefinition(dim,FarmWorkOrder(scene.area(12,14,z1=-3,z2=-3),FarmCrop.WHEAT,FarmMode.REPLANT,
            prepareSoil=true,keepSeeds=1),scene.choices(0,7),3,scene.start,returnTo=scene.start,budget=budget))
        wood.give(Items.IRON_AXE);wood.give(Items.IRON_SHOVEL);wood.give(Items.COARSE_DIRT,8);wood.give(Items.OAK_LOG,5)
        scene.level.setBlock(scene.pos(14,0,3),Blocks.DIRT.defaultBlockState(),3)
        for(y in 1..3)scene.level.setBlock(scene.pos(14,y,3),Blocks.OAK_LOG.defaultBlockState(),3)
        wood.assign(LumberjackTaskDefinition(dim,scene.area(12,16,5,1,5),WoodSelection(listOf("samcnpc:oak")),scene.block(0,1,7),3,budget=budget,version=2))
    }
    private fun sample(worker:OperationScene):ZooTaskSample {
        val r=worker.record;val s=worker.npc.snapshot();val p=s.position
        return ZooTaskSample(r.id,r.primary.id,r.primary.remainingTicks,r.frames.size,
            p.x>=origin.x-3 && p.x<=origin.x+33 && p.z>=origin.z-8 && p.z<=origin.z+13 && p.y>=origin.y && p.y<=origin.y+11,
            listOf(s.navigation,s.control,s.blockBreak,s.itemUse,s.rangedAttack).count { it!=null })
    }
    private fun facts()=workers.associate { it.kind.name to it.record.report().toString() }
    private fun capture():Map<String,Any?> = linkedMapOf("case" to "SHARED_TERRAIN_OUTPUT","status" to if(complete) "PASS" else "RUNNING","ticks" to ticks,
        "npcs" to workers.map { it.npcId.toString() },"pressureAt" to pressureAt,"partialMiningDelivery" to partialMiningDelivery,"incidents" to timeline.evidence,"tasks" to facts())
    override fun evidence()=last.ifEmpty { mapOf("case" to "SHARED_TERRAIN_OUTPUT","status" to "SETTING_UP") }
}
