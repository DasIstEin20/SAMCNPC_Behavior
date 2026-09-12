package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object FarmGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1600,batch="farm_wheat")
    fun matureWheatIsHarvestedAndResownWithoutTouchingYoungOrOutsideCrops(helper: GameTestHelper) = replant(helper,FarmCrop.WHEAT)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1600,batch="farm_carrot")
    fun matureCarrotSeparatesActualYieldFromSeedConsumptionAndReserve(helper: GameTestHelper) = replant(helper,FarmCrop.CARROT)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1600,batch="farm_potato")
    fun maturePotatoSeparatesActualYieldFromSeedConsumptionAndReserve(helper: GameTestHelper) = replant(helper,FarmCrop.POTATO)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1600,batch="farm_fractional_drops")
    fun nativeCarrotLootIsCollectedFromFractionalFarmlandFooting(helper: GameTestHelper) = replant(helper,FarmCrop.CARROT,0.9375)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1600,batch="farm_falling_drops")
    fun fallingNativeCarrotLootWaitsForPhysicalPickupAccess(helper: GameTestHelper) = replant(helper,FarmCrop.CARROT,3.5)
    private fun replant(h: GameTestHelper,crop: FarmCrop,dropHeight: Double? = null) {
        val arena=CombatGameTestArena(h); val output=chest(h,0,3)
        if (dropHeight != null) for(x in 3..7) for(z in -2..2) h.setBlock(BlockPos(x,0,z),Blocks.FARMLAND)
        var redirected=0
        val dropFixture=object {
            @net.minecraftforge.eventbus.api.SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST)
            fun farmDropFootingCompletion(event: NpcActionCompletedEvent) {
                if(dropHeight == null || event.handle.npcUuid != arena.body.uuid || event.result.channel != NpcActionChannel.BLOCK_ACTION || event.result.status != NpcActionStatus.SUCCEEDED) return
                val p=h.absolutePos(BlockPos(4,1,0))
                val drops=h.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java,net.minecraft.world.phys.AABB(p).inflate(1.0))
                    .filter { it.item.`is`(item(crop)) }
                // Controlled loot position, not synthetic yield: retain the actual native
                // stacks and pickup delay produced by the confirmed mature-crop removal.
                for(drop in drops) {
                    redirected+=drop.item.count
                    drop.setPos(p.x+0.5,h.absolutePos(BlockPos.ZERO).y+dropHeight,p.z+0.5)
                    drop.deltaMovement=net.minecraft.world.phys.Vec3.ZERO
                }
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(this)
            }
        }
        plant(h,crop,4,7); plant(h,crop,6,0); plant(h,crop,9,7)
        arena.onReady { npc ->
            if(dropHeight != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(dropFixture)
            arena.give(npc,ItemStack(Items.IRON_HOE)); arena.give(npc,ItemStack(seed(crop),2))
            if (crop == FarmCrop.WHEAT) arena.give(npc,ItemStack(Items.WHEAT,5))
            val work=FarmWorkOrder(WorkArea(WorkBox(block(h,4,1,0),block(h,6,1,0))),crop,FarmMode.REPLANT,prepareSoil=true,keepSeeds=1)
            arena.assign(npc,definition(h,npc,work,1))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            val s=checkNotNull(record.primary.farming)
            if(dropHeight != null) { net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(dropFixture); check(redirected > 0) }
            check(record.status == TaskStatus.COMPLETED) { status(h,npc) }
            check(s.harvested == mapOf(block(h,4,1,0) to 1) && s.replanted == s.harvested)
            check(h.getBlockState(BlockPos(4,1,0)).`is`(cropBlock(crop)))
            check(h.getBlockState(BlockPos(6,1,0)).`is`(cropBlock(crop)) && h.getBlockState(BlockPos(9,1,0)).getValue(CropBlock.AGE) == 7)
            val row=s.resources.physical.entries.getValue(crop.itemId)
            check(count(output,item(crop)) == row.gathered && row.delivered == row.gathered && row.gathered > 0) { "yield origin differs from real output: $row" }
            check(s.resources.physical.entries[crop.seedId]?.consumed == 1)
            check(TaskDelivery.inventoryCount(npc,crop.seedId) >= 1)
            if (crop == FarmCrop.WHEAT) check(TaskDelivery.inventoryCount(npc,crop.itemId) == 5)
            else check(TaskDelivery.inventoryCount(npc,crop.itemId) == 1)
            arena.succeed(npc,record)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1200,batch="farm_harvest_only")
    fun harvestOnlyLeavesTheRealCropCellEmptyWithoutSpendingSeedsOrTilling(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val output=chest(helper,0,3); plant(helper,FarmCrop.WHEAT,4,7)
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.WHEAT,4)); arena.give(npc,ItemStack(Items.WHEAT_SEEDS,2))
            arena.assign(npc,definition(helper,npc,FarmWorkOrder(single(helper),FarmCrop.WHEAT,FarmMode.HARVEST),1))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            val s=checkNotNull(record.primary.farming)
            check(record.status == TaskStatus.COMPLETED && s.totalHarvests() == 1) { status(helper,npc) }
            check(helper.getBlockState(BlockPos(4,1,0)).isAir && s.planted.isEmpty() && s.tilled.isEmpty())
            check(count(output,Items.WHEAT) == 1 && TaskDelivery.inventoryCount(npc,"minecraft:wheat") == 4)
            check(s.resources.physical.entries["minecraft:wheat_seeds"]?.consumed == 0)
            arena.succeed(npc,record)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=3000,batch="farm_cultivation_reload")
    fun cultivationUsesRealHoeAndSourceSeedsThenSurvivesGrowthWaitReloadAndResowsItsYield(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val output=chest(helper,0,3); val source=chest(helper,8,3)
        helper.setBlock(BlockPos(4,0,0),Blocks.DIRT); helper.setBlock(BlockPos(5,0,0),Blocks.GRASS_BLOCK)
        source.setItem(0,ItemStack(Items.WHEAT_SEEDS,10)); source.setChanged()
        var reloaded=false; var grown=false
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_HOE))
            val work=FarmWorkOrder(WorkArea(WorkBox(block(helper,4,1,0),block(helper,5,1,0))),FarmCrop.WHEAT,FarmMode.CULTIVATE,
                prepareSoil=true,seedSources=ContainerChoices(listOf(block(helper,8,1,3))),keepSeeds=1,sourceKeepSeeds=2,growthWaitTicks=400,growthCheckTicks=20)
            arena.assign(npc,definition(helper,npc,work,2).copy(budget=TaskBudget(2800)))
        }
        arena.observe { npc,record ->
            val s=checkNotNull(record.primary.farming)
            if (!reloaded && s.phase == FarmPhase.WAIT_GROWTH && s.planted.size == 2) {
                val server=helper.level.server
                check(TaskService.pause(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                val saved=TaskStore.forServer(server).save(CompoundTag())
                check(BehaviorRuntimeService.reload().accepted)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks",TaskStore.load(saved))
                check(TaskService.resume(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                reloaded=true
            }
            if (reloaded && !grown && s.phase == FarmPhase.WAIT_GROWTH) {
                for (x in 4..5) helper.setBlock(BlockPos(x,1,0),(Blocks.WHEAT as CropBlock).getStateForAge(7))
                grown=true
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && reloaded && grown) { status(helper,npc) }
                check(s.harvested.size == 2 && s.replanted == s.harvested && s.planted.values.sum() == 4)
                check(s.tilled.values.sum() >= 2 && s.resources.physical.entries["minecraft:wheat_seeds"]?.consumed == 4)
                check(record.logistics.outcomes.isNotEmpty() && record.logistics.outcomes.all { it.returned })
                val withdrawn=s.resources.physical.entries["minecraft:wheat_seeds"]?.supplied ?: 0
                check(count(source,Items.WHEAT_SEEDS) == 10-withdrawn && count(source,Items.WHEAT_SEEDS) >= 2)
                check(count(output,Items.WHEAT) == 2 && TaskDelivery.inventoryCount(npc,"minecraft:wheat_seeds") >= 1)
                val hoe=npc.inventoryContents().first { it.stack.itemId == "minecraft:iron_hoe" }
                check(hoe.stack.damage == s.tilled.values.sum()) { "real hoe wear differs from soil actions" }
                for (x in 4..5) check(helper.getBlockState(BlockPos(x,1,0)).`is`(Blocks.WHEAT))
                arena.succeed(npc,record)
            }
        }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=900,batch="farm_growth_timeout")
    fun immatureCropWaitEndsExplicitlyWithoutHarvestingOrCreditingInitialYield(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val output=chest(helper,0,3); plant(helper,FarmCrop.WHEAT,4,0)
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.WHEAT,4))
            arena.assign(npc,definition(helper,npc,FarmWorkOrder(single(helper),FarmCrop.WHEAT,FarmMode.CULTIVATE,growthWaitTicks=100,growthCheckTicks=20),1).copy(budget=TaskBudget(700)))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            val s=checkNotNull(record.primary.farming)
            check(record.status == TaskStatus.FAILED && s.stop == FarmProblem.GROWTH_TIMEOUT && s.harvested.isEmpty()) { status(helper,npc) }
            check(helper.getBlockState(BlockPos(4,1,0)).`is`(Blocks.WHEAT) && count(output,Items.WHEAT) == 0)
            check(TaskDelivery.inventoryCount(npc,"minecraft:wheat") == 4); arena.succeed(npc,record)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1100,batch="farm_no_seeds")
    fun missingSeedSourcePreservesActualSoilPreparationAndReportsPartialInsteadOfCreatingSeeds(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); chest(helper,0,3); helper.setBlock(BlockPos(4,0,0),Blocks.DIRT)
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_HOE))
            arena.assign(npc,definition(helper,npc,FarmWorkOrder(single(helper),FarmCrop.WHEAT,FarmMode.CULTIVATE,prepareSoil=true),1))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            val s=checkNotNull(record.primary.farming)
            check(record.status == TaskStatus.FAILED && s.stop == FarmProblem.MISSING_SEEDS && s.tilled.values.sum() == 1) { status(helper,npc) }
            check(s.planted.isEmpty() && helper.getBlockState(BlockPos(4,1,0)).isAir && TaskDelivery.inventoryCount(npc,"minecraft:wheat_seeds") == 0)
            check(npc.inventoryContents().first { it.stack.itemId == "minecraft:iron_hoe" }.stack.damage == 1)
            arena.succeed(npc,record)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1900,batch="farm_command_amend")
    fun realFarmCommandDefersRecipientChangeUntilRequiredReplantIsComplete(helper: GameTestHelper) {
        val actor=GameTestActor(helper.level,"FarmAmend"); val arena=CombatGameTestArena(helper,actor.player)
        val oldOutput=chest(helper,0,3); val output=chest(helper,0,-3); plant(helper,FarmCrop.WHEAT,4,7)
        var amended=false; val server=helper.level.server
        fun xyz(p: NpcBlockPosition)="${p.x} ${p.y} ${p.z}"
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_HOE)); arena.give(npc,ItemStack(Items.WHEAT_SEEDS,2))
            val target=xyz(block(helper,4,1,0))
            val command="samcnpc behavior task assign AmendProof farm replant wheat $target $target ${xyz(block(helper,0,1,3))} 1 1 true 1 1800"
            check(server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),command) == 1) { "real farm command failed" }
        }
        arena.observe { npc,record ->
            val s=checkNotNull(record.primary.farming)
            if (!amended && s.phase == FarmPhase.COLLECT) {
                check(TaskAmendments.automatic(server,npc,actor.player,TaskChange.Redirect(ContainerChoices(listOf(block(helper,0,1,-3))))).status == NpcActionStatus.SUCCEEDED)
                check(record.amendments.pending != null && record.amendments.revision == 0)
                amended=true
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && amended && record.amendments.revision == 1) { status(helper,npc) }
                check(count(oldOutput,Items.WHEAT) == 0 && count(output,Items.WHEAT) == 1 && s.replanted.values.sum() == 1)
                actor.close(); arena.succeed(npc,record)
            }
        }
    }
    private fun definition(h: GameTestHelper,npc: NpcFacade,w: FarmWorkOrder,quantity: Int)=FarmTaskDefinition(npc.snapshot().dimensionId,w,
        ContainerChoices(listOf(block(h,0,1,3))),quantity,npc.snapshot().position,returnTo=npc.snapshot().position,budget=TaskBudget(1500))
    private fun single(h: GameTestHelper)=WorkArea(WorkBox(block(h,4,1,0),block(h,4,1,0)))
    private fun plant(h: GameTestHelper,crop: FarmCrop,x: Int,age: Int) { h.setBlock(BlockPos(x,0,0),Blocks.FARMLAND); h.setBlock(BlockPos(x,1,0),(cropBlock(crop) as CropBlock).getStateForAge(age)) }
    private fun cropBlock(crop: FarmCrop)=when(crop) { FarmCrop.WHEAT -> Blocks.WHEAT; FarmCrop.CARROT -> Blocks.CARROTS; FarmCrop.POTATO -> Blocks.POTATOES }
    private fun item(crop: FarmCrop)=when(crop) { FarmCrop.WHEAT -> Items.WHEAT; FarmCrop.CARROT -> Items.CARROT; FarmCrop.POTATO -> Items.POTATO }
    private fun seed(crop: FarmCrop)=if (crop == FarmCrop.WHEAT) Items.WHEAT_SEEDS else item(crop)
    private fun chest(h: GameTestHelper,x: Int,z: Int): ChestBlockEntity { h.setBlock(BlockPos(x,1,z),Blocks.CHEST); return h.level.getBlockEntity(h.absolutePos(BlockPos(x,1,z))) as ChestBlockEntity }
    private fun block(h: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition { val p=h.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z) }
    private fun count(c: ChestBlockEntity,item: Item)=(0 until c.containerSize).sumOf { if (c.getItem(it).`is`(item)) c.getItem(it).count else 0 }
    private fun status(h: GameTestHelper,npc: NpcFacade)=TaskService.status(h.level.server,npc.npcUuid).orEmpty()
}
