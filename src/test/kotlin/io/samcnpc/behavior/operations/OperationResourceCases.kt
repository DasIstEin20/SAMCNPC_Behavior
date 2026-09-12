package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock

internal object OperationResourceCases {
    fun prepare(s: OperationScene) {
        val dim=s.npc.snapshot().dimensionId
        val budget=TaskBudget(6000)
        s.makeChest(0,7)
        when(s.kind) {
            OperationKind.MINING, OperationKind.AMEND_RESOURCE -> {
                s.give(Items.IRON_PICKAXE); s.give(Items.RAW_IRON,5)
                for(x in 12..13) s.level.setBlock(s.pos(x,1,0),Blocks.IRON_ORE.defaultBlockState(),3)
                if(s.kind == OperationKind.AMEND_RESOURCE) {
                    s.give(Items.RAW_GOLD,3)
                    for(x in 20..21) s.level.setBlock(s.pos(x,1,0),Blocks.GOLD_ORE.defaultBlockState(),3)
                }
                s.assign(MiningTaskDefinition(dim,MiningWorkOrder(s.area(12,13),MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:iron_ore"))),
                    WorkResourceIds(listOf("minecraft:raw_iron")),s.choices(0,7),2,MiningCounting.DELIVERED_ITEMS,s.start,returnTo=s.start,budget=budget))
            }
            OperationKind.FARM -> {
                s.give(Items.IRON_HOE); s.give(Items.WHEAT_SEEDS,5); s.give(Items.WHEAT,5)
                for(x in 12..13) s.level.setBlock(s.pos(x,0,0),Blocks.DIRT.defaultBlockState(),3)
                // The elevated field needs a water bed; an open shaft loses native drops far below it.
                s.level.setBlock(s.pos(14,-1,0),Blocks.STONE.defaultBlockState(),3)
                s.level.setBlock(s.pos(14,0,0),Blocks.WATER.defaultBlockState(),3)
                s.assign(FarmTaskDefinition(dim,FarmWorkOrder(s.area(12,13),FarmCrop.WHEAT,FarmMode.CULTIVATE,
                    prepareSoil=true,keepSeeds=1,growthWaitTicks=600,growthCheckTicks=20),s.choices(0,7),2,s.start,returnTo=s.start,budget=budget))
            }
            OperationKind.PLANTING -> {
                s.give(Items.DARK_OAK_SAPLING,6)
                for(x in 12..13) for(z in 0..1) s.level.setBlock(s.pos(x,0,z),Blocks.DIRT.defaultBlockState(),3)
                s.assign(PlantingTaskDefinition(dim,PlantingWorkOrder(s.area(12,13,z2=1),SaplingSpecies.DARK_OAK,PlantingMode.GAPS,
                    positions=listOf(s.block(12,1,0)),keepSaplings=2),1,s.start,returnTo=s.start,budget=budget))
            }
            OperationKind.WOOD, OperationKind.WOOD_REPLANT -> {
                s.give(Items.IRON_AXE); s.give(Items.IRON_SHOVEL); s.give(Items.COARSE_DIRT,8); s.give(Items.OAK_LOG,5)
                s.level.setBlock(s.pos(12,0,0),Blocks.DIRT.defaultBlockState(),3)
                for(y in 1..3) s.level.setBlock(s.pos(12,y,0),Blocks.OAK_LOG.defaultBlockState(),3)
                val replant=if(s.kind == OperationKind.WOOD_REPLANT) {
                    s.give(Items.OAK_SAPLING,2)
                    PlantingTaskDefinition(dim,PlantingWorkOrder(s.area(12,12),SaplingSpecies.OAK,PlantingMode.GAPS,
                        positions=listOf(s.block(12,1,0)),keepSaplings=1),1,s.start,returnTo=s.start,budget=budget)
                } else null
                s.assign(LumberjackTaskDefinition(dim,s.area(10,14,5,-2,2),WoodSelection(listOf("samcnpc:oak")),s.block(0,1,7),3,
                    budget=budget,version=2,replant=replant))
            }
            else -> error("${s.kind} is not a resource case")
        }
    }
    fun checkpoint(s: OperationScene): Boolean {
        val frame=s.record.primary
        return when(s.kind) {
            OperationKind.MINING -> frame.mining?.selection?.removed?.size == 1
            OperationKind.AMEND_RESOURCE -> frame.mining?.let { it.phase == MiningPhase.SELECT && it.selection.removed.size == 1 && it.resources.available("minecraft:raw_iron") == 1 } == true
            OperationKind.FARM -> frame.farming?.planted?.size == 1
            OperationKind.PLANTING -> frame.planting?.planted() == 2
            OperationKind.WOOD -> frame.lumberjack?.observedRemovedBlocks?.size == 1
            OperationKind.WOOD_REPLANT -> frame.lumberjack?.replantDefinition != null && frame.planting?.planted() == 0 && s.count(0,7,Items.OAK_LOG) == 3
            else -> false
        }
    }
    /** Controlled crop growth is a world input; sowing, tilling, harvest and items remain native. */
    fun advanceWorldInput(s: OperationScene) {
        if(s.kind != OperationKind.FARM || s.record.status == TaskStatus.PAUSED) return
        if(s.record.primary.farming?.phase != FarmPhase.WAIT_GROWTH) return
        for(x in 12..13) {
            val at=s.pos(x,1,0)
            if(s.level.getBlockState(at).`is`(Blocks.WHEAT)) s.level.setBlock(at,(Blocks.WHEAT as CropBlock).getStateForAge(7),3)
        }
    }
    fun verify(s: OperationScene) {
        s.requireCompleted()
        when(s.kind) {
            OperationKind.MINING -> {
                check((12..13).all { s.level.getBlockState(s.pos(it,1,0)).isAir })
                check(s.count(0,7,Items.RAW_IRON) == 2 && s.carried("minecraft:raw_iron") == 5)
                val state=checkNotNull(s.record.primary.mining)
                check(state.selection.removed.size == 2 && state.resources.physical.entries.values.all { it.valid() })
                s.requireReturned()
            }
            OperationKind.AMEND_RESOURCE -> {
                val air=(12..13).count { s.level.getBlockState(s.pos(it,1,0)).isAir }
                val iron=(12..13).count { s.level.getBlockState(s.pos(it,1,0)).`is`(Blocks.IRON_ORE) }
                check(air == 1 && iron == 1 && (20..21).all { s.level.getBlockState(s.pos(it,1,0)).isAir })
                check(s.count(0,7,Items.RAW_IRON) == 0 && s.carried("minecraft:raw_iron") == 6)
                check(s.count(0,7,Items.RAW_GOLD) == 2 && s.carried("minecraft:raw_gold") == 3)
                check(s.record.amendments.revision == 1 && s.record.amendments.objectives.size == 1)
                check(s.record.primary.mining?.selection?.removed?.size == 2)
                s.requireReturned()
            }
            OperationKind.FARM -> {
                val state=checkNotNull(s.record.primary.farming)
                check(state.harvested.size == 2 && state.tilled.size == 2 && state.replanted == state.harvested)
                check((12..13).all { s.level.getBlockState(s.pos(it,1,0)).`is`(Blocks.WHEAT) })
                check(s.count(0,7,Items.WHEAT) == 2 && s.carried("minecraft:wheat") == 5 && s.carried("minecraft:wheat_seeds") >= 1)
                check(state.resources.physical.entries.values.all { it.valid() }); s.requireReturned()
            }
            OperationKind.PLANTING -> {
                check((12..13).all { x -> (0..1).all { z -> s.level.getBlockState(s.pos(x,1,z)).`is`(Blocks.DARK_OAK_SAPLING) } })
                check(s.record.primary.planting?.planted() == 4 && s.carried("minecraft:dark_oak_sapling") == 2)
                check(s.record.primary.planting?.resources?.entries?.get("minecraft:dark_oak_sapling")?.consumed == 4)
                s.requireReturned()
            }
            OperationKind.WOOD, OperationKind.WOOD_REPLANT -> {
                check(s.count(0,7,Items.OAK_LOG) == 3 && s.carried("minecraft:oak_log") == 5)
                check(s.carried("minecraft:coarse_dirt") == 8)
                check(s.record.primary.lumberjack?.observedRemovedBlocks?.containsAll((1..3).map { s.block(12,it,0) }) == true)
                check((2..3).all { s.level.getBlockState(s.pos(12,it,0)).isAir })
                if(s.kind == OperationKind.WOOD_REPLANT) {
                    check(s.level.getBlockState(s.pos(12,1,0)).`is`(Blocks.OAK_SAPLING) && s.carried("minecraft:oak_sapling") == 1)
                    check(s.record.primary.planting?.planted() == 1); s.requireReturned()
                } else check(s.level.getBlockState(s.pos(12,1,0)).isAir)
            }
            else -> error("${s.kind} is not a resource result")
        }
    }
}
