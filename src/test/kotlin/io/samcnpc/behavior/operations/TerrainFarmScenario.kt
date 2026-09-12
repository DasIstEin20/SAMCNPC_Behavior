package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.FarmBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraftforge.registries.ForgeRegistries
import net.minecraft.resources.ResourceLocation

internal enum class TerrainFarmCase(val crop:FarmCrop) {
    TRAMPLED_WHEAT(FarmCrop.WHEAT), TRAMPLED_CARROT(FarmCrop.CARROT), TRAMPLED_POTATO(FarmCrop.POTATO),
    REPEATED_TRAMPLING(FarmCrop.WHEAT), SEEDS_WITHDRAWN(FarmCrop.WHEAT), FOREIGN_BLOCK(FarmCrop.WHEAT), WATER_REPLACES_SOIL(FarmCrop.WHEAT),
}

/** Actual crop yield and seed spending with a world/inventory change just before replanting. */
internal class TerrainFarmScenario(server:MinecraftServer,val kind:TerrainFarmCase,seed:Long):ZooScenario {
    override val scene=OperationScene.create(server.overworld(),OperationKind.FARM,BlockPos(5200+kind.ordinal*64,80,1000))
    private val crop=kind.crop
    private val retill=kind.ordinal<=TerrainFarmCase.TRAMPLED_POTATO.ordinal
    private val target=scene.block(12,1,0)
    private val timeline=ZooIncidents(seed,(1..if(kind==TerrainFarmCase.REPEATED_TRAMPLING) 3 else 1).map {
        ZooIncident("${kind.name}_$it","before_replant",mapOf("crop" to crop.name,"seedReserve" to "1")) })
    private var initialTotal=0;private var created:Int?=null;private var withdrawn=0
    private var foreignChest:BlockPos?=null
    private var ticks=0;private var ready=false;private var quiet=0;private var oracle:ZooTaskOracle?=null
    private var last=emptyMap<String,Any?>()
    override var complete=false
        private set

    override fun tick() {
        if(complete)return
        check(++ticks<6200) { "$kind timed out ${if(ready) scene.record.report() else "loading"}" }
        if(!ready) {
            if(!scene.loaded || !scene.body.onGround())return
            prepare();initialTotal=pool();oracle=ZooTaskOracle(sample());ready=true
        }
        val record=scene.record;val state=checkNotNull(record.primary.farming)
        checkNotNull(oracle).observe(sample())
        check(scene.body.isAlive)
        if(created==null && state.phase==FarmPhase.COLLECT) {
            check(state.harvested[target]==1 && state.planted.isEmpty())
            created=pool()-initialTotal;check(checkNotNull(created)>0)
        }
        if(!timeline.complete && state.phase==FarmPhase.PLANT &&
            (kind!=TerrainFarmCase.REPEATED_TRAMPLING || scene.level.getBlockState(scene.pos(12,0,0)).`is`(Blocks.FARMLAND))) {
            check(state.harvested[target]==1 && state.planted.isEmpty() && created!=null)
            val before=facts();val soil=scene.pos(12,0,0)
            when(kind) {
                TerrainFarmCase.TRAMPLED_WHEAT,TerrainFarmCase.TRAMPLED_CARROT,TerrainFarmCase.TRAMPLED_POTATO,TerrainFarmCase.REPEATED_TRAMPLING ->
                    FarmBlock.turnToDirt(null,scene.level.getBlockState(soil),scene.level,soil)
                TerrainFarmCase.FOREIGN_BLOCK -> scene.level.setBlock(scene.pos(12,1,0),Blocks.GOLD_BLOCK.defaultBlockState(),3)
                TerrainFarmCase.WATER_REPLACES_SOIL -> {
                    scene.level.setBlock(scene.pos(12,-1,0),Blocks.STONE.defaultBlockState(),3)
                    scene.level.setBlock(soil,Blocks.WATER.defaultBlockState(),3)
                }
                TerrainFarmCase.SEEDS_WITHDRAWN -> {
                    val at=BlockPos.containing(scene.body.x+2.0,scene.origin.y+1.0,scene.body.z+2.0)
                    check(at!=scene.pos(12,1,0) && scene.level.getBlockState(at).isAir)
                    scene.level.setBlock(at,Blocks.CHEST.defaultBlockState(),3);foreignChest=at
                    val count=scene.carried(crop.seedId)-1;check(count in 1..64)
                    val source=scene.npc.inventoryContents().first { it.stack.itemId==crop.seedId && it.stack.count>=count }
                    val transfer=scene.npc.transferToContainer(source.slot,NpcContainerTransferRequest(NpcContainerEndpoint(scene.npc.snapshot().dimensionId,
                        NpcBlockPosition(at.x,at.y,at.z)),0,crop.seedId,count))
                    check(!transfer.uncertain && transfer.movedCount==count) { transfer.toString() };withdrawn=count
                }
            }
            timeline.record(checkNotNull(timeline.pending("before_replant")),ticks,before,facts())
        }
        last=capture()
        if(!record.status.terminal)return
        check(timeline.complete && created!=null)
        check(state.harvested==mapOf(target to 1)) { "$kind changed the confirmed harvest" }
        check(!state.resources.physical.uncertain && state.resources.physical.entries.values.all { it.valid() })
        val definition=record.primary.definition as FarmTaskDefinition
        val outputItem=checkNotNull(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(crop.itemId)))
        val delivered=scene.count(0,7,outputItem)
        check(delivered==state.delivered(definition) && delivered>=1)
        if(retill) scene.requireCompleted()
        val planted=if(retill) 1 else 0
        ZooResourceOracle.conserve(initialTotal.toLong(),pool().toLong(),produced=checkNotNull(created).toLong(),consumed=planted.toLong())
        check(scene.carried(crop.seedId)>=1) { "$kind consumed its reserved final seed" }
        if(retill) {
            scene.requireCompleted()
            check(state.tilled[target]==1 && state.planted[target]==1 && state.replanted[target]==1)
            val block=scene.level.getBlockState(scene.pos(12,1,0));check(block.block is CropBlock && (block.block as CropBlock).getAge(block)==0)
        } else {
            check(record.status==TaskStatus.FAILED && record.reason==TaskReason.WORK_FAILED && state.planted.isEmpty()) { "$kind ${record.report()}" }
            check(state.stop==when(kind) { TerrainFarmCase.SEEDS_WITHDRAWN -> FarmProblem.MISSING_SEEDS; TerrainFarmCase.REPEATED_TRAMPLING -> FarmProblem.OBSERVATION_LIMIT; else -> FarmProblem.INVALID_SOIL })
            when(kind) {
                TerrainFarmCase.REPEATED_TRAMPLING -> check(state.tilled[target]==2 && scene.level.getBlockState(scene.pos(12,0,0)).`is`(Blocks.DIRT))
                TerrainFarmCase.SEEDS_WITHDRAWN -> check(withdrawn>0 && scene.carried(crop.seedId)==1 && scene.level.getBlockState(scene.pos(12,1,0)).isAir)
                TerrainFarmCase.FOREIGN_BLOCK -> check(scene.level.getBlockState(scene.pos(12,1,0)).`is`(Blocks.GOLD_BLOCK))
                TerrainFarmCase.WATER_REPLACES_SOIL -> check(scene.level.getBlockState(scene.pos(12,0,0)).`is`(Blocks.WATER))
                else -> error("unexpected failed farm case")
            }
        }
        scene.requireReturned();scene.requireReleased()
        if(++quiet<40)return
        checkNotNull(oracle).observe(sample(),released=true)
        complete=true;last=capture();scene.close()
    }
    private fun prepare() {
        scene.makeChest(0,7);scene.give(Items.IRON_HOE)
        val seed=checkNotNull(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(crop.seedId)))
        val output=checkNotNull(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(crop.itemId)))
        scene.give(seed,5);if(seed!=output)scene.give(output,5)
        val block=checkNotNull(ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse(crop.blockId))) as CropBlock
        scene.level.setBlock(scene.pos(12,0,0),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7),3)
        scene.level.setBlock(scene.pos(12,1,0),block.getStateForAge(block.maxAge),3)
        scene.level.setBlock(scene.pos(14,-1,0),Blocks.STONE.defaultBlockState(),3)
        scene.level.setBlock(scene.pos(14,0,0),Blocks.WATER.defaultBlockState(),3)
        scene.assign(FarmTaskDefinition(scene.npc.snapshot().dimensionId,FarmWorkOrder(scene.area(12,12),crop,FarmMode.REPLANT,
            prepareSoil=true,keepSeeds=1),scene.choices(0,7),1,scene.start,returnTo=scene.start,budget=TaskBudget(6000)))
    }
    private fun pool():Int {
        val items=setOf(crop.itemId,crop.seedId)
        fun count(chest:ChestBlockEntity)=(0 until chest.containerSize).sumOf { val stack=chest.getItem(it);if(ForgeRegistries.ITEMS.getKey(stack.item).toString() in items) stack.count else 0 }
        val foreign=foreignChest?.let { scene.level.getBlockEntity(it) as? ChestBlockEntity }
        val drops=scene.level.getEntitiesOfClass(ItemEntity::class.java,AABB(scene.pos(-3,0,-8),scene.pos(33,12,13)))
            .sumOf { if(ForgeRegistries.ITEMS.getKey(it.item.item).toString() in items) it.item.count else 0 }
        return items.sumOf(scene::carried)+count(scene.chest(0,7))+(foreign?.let(::count) ?: 0)+drops
    }
    private fun sample():ZooTaskSample {
        val r=scene.record;val s=scene.npc.snapshot();val d=r.primary.definition as FarmTaskDefinition
        return ZooTaskSample(r.id,r.primary.id,r.primary.remainingTicks,r.frames.size,d.contains(s.position),listOf(s.navigation,s.control,s.blockBreak,s.itemUse,s.rangedAttack).count { it!=null })
    }
    private fun facts()=mapOf("pool" to pool().toString(),"seeds" to scene.carried(crop.seedId).toString(),"phase" to scene.record.primary.farming?.phase.toString(),"soil" to scene.level.getBlockState(scene.pos(12,0,0)).toString())
    private fun capture():Map<String,Any?> = linkedMapOf("case" to kind.name,"status" to if(complete) "PASS" else "RUNNING","npc" to scene.npcId.toString(),"ticks" to ticks,"nativeCreated" to created,
        "withdrawnSeeds" to withdrawn,"incidents" to timeline.evidence,"task" to scene.record.report(),"facts" to facts())
    override fun evidence()=last.ifEmpty { mapOf("case" to kind.name,"status" to "SETTING_UP") }
}
