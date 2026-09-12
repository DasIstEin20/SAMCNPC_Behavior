package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object FoodGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1000,batch="food_drops")
    fun edibleDropsRespectAreaProtectGiftsAndDoNotEnableHunting(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val output=chest(helper,0,3)
        val pig=arena.mob(EntityType.PIG,6.0,1.0)
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.BREAD,5))
            arena.assign(npc,definition(helper,npc,FoodWorkOrder.Drops(area(helper)),3,9).copy(outputs=WorkResourceIds(listOf("minecraft:bread","minecraft:stone"))))
            arena.give(npc,ItemStack(Items.BREAD,4))
            drop(helper,Items.BREAD,3,5,0); drop(helper,Items.STONE,2,4,1)
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            val s=checkNotNull(record.primary.food)
            check(record.status == TaskStatus.COMPLETED) { status(helper,npc) }
            check(s.pickups == mapOf("minecraft:bread" to 3)) { "food pickup origin differs: ${s.pickups}" }
            check(s.resources.entries.getValue("minecraft:bread").protectedGathered == 4)
            check(count(output,Items.BREAD) == 3 && TaskDelivery.inventoryCount(npc,"minecraft:bread") == 9)
            check(count(output,Items.STONE) == 0 && s.resources.delivered("minecraft:stone") == 0)
            check(pig.isAlive && pig.health == pig.maxHealth && s.hunts.isEmpty())
            arena.succeed(npc,record)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1500,batch="food_stored_reload")
    fun storedFoodPreservesSourceAndNpcRationsAcrossPauseAndTaskStoreReload(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val output=chest(helper,0,3); val source=chest(helper,6,0)
        source.setItem(0,ItemStack(Items.BREAD,12)); source.setChanged()
        var reloaded=false
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.BREAD,2))
            arena.assign(npc,definition(helper,npc,FoodWorkOrder.Stored(ContainerChoices(listOf(block(helper,6,1,0))),4),5,4))
            arena.give(npc,ItemStack(Items.BREAD,1))
        }
        arena.observe { npc,record ->
            val s=checkNotNull(record.primary.food)
            if (!reloaded && (s.resources.entries["minecraft:bread"]?.sourceOutput ?: 0) > 0) {
                val server=helper.level.server
                check(TaskService.pause(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                val saved=TaskStore.forServer(server).save(CompoundTag())
                check(BehaviorRuntimeService.reload().accepted)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks",TaskStore.load(saved))
                check(TaskService.resume(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                reloaded=true
            }
            if (record.status.terminal) {
                check(reloaded && record.status == TaskStatus.COMPLETED) { status(helper,npc) }
                check(s.pickups.isEmpty() && s.resources.entries.getValue("minecraft:bread").sourceOutput == 6)
                check(count(source,Items.BREAD) == 6 && count(output,Items.BREAD) == 5 && TaskDelivery.inventoryCount(npc,"minecraft:bread") == 4)
                check(s.resources.entries.getValue("minecraft:bread").protectedGathered == 1)
                arena.succeed(npc,record)
            }
        }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1400,batch="food_berries")
    fun ripeWildBerriesUseNativeHarvestAndPreserveImmatureBushAndInitialRation(helper: GameTestHelper) = berries(helper,true)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=700,batch="food_immature")
    fun immatureWildFoodEndsPartialWithoutDestroyingBushOrDeliveringInitialStock(helper: GameTestHelper) = berries(helper,false)
    private fun berries(helper: GameTestHelper,ripe: Boolean) {
        val arena=CombatGameTestArena(helper); val output=chest(helper,0,3)
        for (x in listOf(4,7)) helper.setBlock(BlockPos(x,0,0),Blocks.DIRT)
        helper.setBlock(BlockPos(4,1,0),Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE,if (ripe) 3 else 0))
        helper.setBlock(BlockPos(7,1,0),Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE,0))
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.SWEET_BERRIES,4))
            arena.assign(npc,definition(helper,npc,FoodWorkOrder.Berries(area(helper)),2,4).copy(outputs=WorkResourceIds(listOf(FoodTaskDefinition.BERRY_ITEM))))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            val s=checkNotNull(record.primary.food)
            check(record.status == if (ripe) TaskStatus.COMPLETED else TaskStatus.FAILED) { status(helper,npc) }
            check(s.harvested == if (ripe) setOf(block(helper,4,1,0)) else emptySet<NpcBlockPosition>()) { "harvested wrong bush: ${s.harvested}" }
            check(helper.getBlockState(BlockPos(4,1,0)).`is`(Blocks.SWEET_BERRY_BUSH) && helper.getBlockState(BlockPos(7,1,0)).`is`(Blocks.SWEET_BERRY_BUSH))
            check(count(output,Items.SWEET_BERRIES) == if (ripe) 2 else 0)
            val gathered=s.pickups[FoodTaskDefinition.BERRY_ITEM] ?: 0
            check(if (ripe) gathered in 2..3 else gathered == 0)
            check(TaskDelivery.inventoryCount(npc,FoodTaskDefinition.BERRY_ITEM) == 4+gathered-(if (ripe) 2 else 0))
            arena.succeed(npc,record)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1700,batch="food_hunt")
    fun explicitCowHuntUsesConfirmedCombatAndActualFoodLeavingOtherTargetsUntouched(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val output=chest(helper,0,3)
        val cow=arena.mob(EntityType.COW,5.0,0.0,4.0)
        val pig=arena.mob(EntityType.PIG,4.0,1.0)
        val outside=arena.mob(EntityType.COW,9.0,5.0)
        val trace = java.util.ArrayDeque<String>()
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_SWORD))
            val work=FoodWorkOrder.Hunt(area(helper),NpcEntityTypeFilter.of(setOf("minecraft:cow")),2)
            arena.assign(npc,definition(helper,npc,work,1,0).copy(outputs=WorkResourceIds(listOf("minecraft:beef"))))
        }
        arena.observe { npc,record ->
            if (trace.size == 160) trace.removeFirst()
            val drops = helper.level.getEntitiesOfClass(ItemEntity::class.java, arena.body.boundingBox.inflate(12.0))
            trace.addLast("tick=${helper.tick} phase=${record.primary.food?.phase} remaining=${record.primary.food?.collectionTicks} position=${npc.snapshot().position} nav=${npc.snapshot().navigation} food=${drops.map { "${it.item}@${it.position()}" }}")
            if (record.status.terminal) {
            if (record.status != TaskStatus.COMPLETED) com.mojang.logging.LogUtils.getLogger().error("HUNT_PICKUP_TRACE {}", trace.joinToString("\n"))
            val s=checkNotNull(record.primary.food)
            check(record.status == TaskStatus.COMPLETED) { status(helper,npc) }
            check(s.hunts == mapOf(cow.uuid to 1) && record.completedInterruptions == 1)
            check(count(output,Items.BEEF) == 1 && (s.pickups["minecraft:beef"] ?: 0) >= 1)
            check(pig.isAlive && pig.health == pig.maxHealth && outside.isAlive && outside.health == outside.maxHealth)
            check(s.harvested.isEmpty()); arena.succeed(npc,record)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=700,batch="food_forbidden_hunt")
    fun explicitHuntingFilterCannotSubstituteNearbyForbiddenAnimal(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val output=chest(helper,0,3); val pig=arena.mob(EntityType.PIG,5.0,0.0)
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_SWORD))
            arena.assign(npc,definition(helper,npc,FoodWorkOrder.Hunt(area(helper),NpcEntityTypeFilter.of(setOf("minecraft:cow"))),1,0).copy(outputs=WorkResourceIds(listOf("minecraft:porkchop"))))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            val s=checkNotNull(record.primary.food)
            check(record.status == TaskStatus.FAILED && s.hunts.isEmpty() && s.pickups.isEmpty()) { status(helper,npc) }
            check(pig.isAlive && pig.health == pig.maxHealth && count(output,Items.PORKCHOP) == 0)
            arena.succeed(npc,record)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1600,batch="food_command_amend")
    fun realFoodCommandAndQuantityRecipientChangesPreserveActualWithdrawalAndTaskIdentity(helper: GameTestHelper) {
        val actor=GameTestActor(helper.level,"FoodAmend"); val arena=CombatGameTestArena(helper,actor.player)
        val source=chest(helper,6,0); val oldOutput=chest(helper,0,3); val output=chest(helper,0,-3)
        source.setItem(0,ItemStack(Items.BREAD,12)); source.setChanged()
        var amended=false; var original: java.util.UUID?=null
        val server=helper.level.server
        fun xyz(p: NpcBlockPosition)="${p.x} ${p.y} ${p.z}"
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.BREAD,2))
            val command="samcnpc behavior task assign AmendProof food stored ${xyz(block(helper,6,1,0))} 4 ${xyz(block(helper,0,1,3))} \"minecraft:bread\" 2 3 1500 true"
            check(server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),command) == 1) { "real food command rejected" }
            original=TaskStore.forServer(server).get(npc.npcUuid)?.id
        }
        arena.observe { npc,record ->
            val s=checkNotNull(record.primary.food)
            if (!amended && (s.resources.entries["minecraft:bread"]?.sourceOutput ?: 0) > 0) {
                check(TaskAmendments.automatic(server,npc,actor.player,TaskChange.Quantity(5,QuantityChangeMode.TOTAL)).status == NpcActionStatus.SUCCEEDED)
                check(TaskAmendments.automatic(server,npc,actor.player,TaskChange.Redirect(ContainerChoices(listOf(block(helper,0,1,-3))))).status == NpcActionStatus.SUCCEEDED)
                amended=true
            }
            if (record.status.terminal) {
                check(amended && original == record.id && record.status == TaskStatus.COMPLETED && record.amendments.revision == 2) { status(helper,npc) }
                check(count(oldOutput,Items.BREAD) == 0 && count(output,Items.BREAD) == 5 && count(source,Items.BREAD) == 6)
                check(TaskDelivery.inventoryCount(npc,"minecraft:bread") == 3 && record.primary.remainingTicks < 1500)
                actor.close(); arena.succeed(npc,record)
            }
        }
    }
    private fun definition(h: GameTestHelper,npc: NpcFacade,work: FoodWorkOrder,quantity: Int,keep: Int) = FoodTaskDefinition(npc.snapshot().dimensionId,work,
        WorkResourceIds(listOf("minecraft:bread")),ContainerChoices(listOf(block(h,0,1,3))),quantity,keep,npc.snapshot().position,returnTo=npc.snapshot().position,budget=TaskBudget(1500))
    private fun area(h: GameTestHelper)=WorkArea(WorkBox(block(h,3,1,-2),block(h,10,3,2)))
    private fun chest(h: GameTestHelper,x: Int,z: Int): ChestBlockEntity { h.setBlock(BlockPos(x,1,z),Blocks.CHEST); return h.level.getBlockEntity(h.absolutePos(BlockPos(x,1,z))) as ChestBlockEntity }
    private fun block(h: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition { val p=h.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z) }
    private fun drop(h: GameTestHelper,item: Item,count: Int,x: Int,z: Int) { val p=h.absolutePos(BlockPos(x,1,z)); val e=ItemEntity(h.level,p.x+0.5,p.y.toDouble(),p.z+0.5,ItemStack(item,count)); e.setNoPickUpDelay(); check(h.level.addFreshEntity(e)) }
    private fun count(c: ChestBlockEntity,item: Item)=(0 until c.containerSize).sumOf { if (c.getItem(it).`is`(item)) c.getItem(it).count else 0 }
    private fun status(h: GameTestHelper,npc: NpcFacade)=TaskService.status(h.level.server,npc.npcUuid).orEmpty()
}
