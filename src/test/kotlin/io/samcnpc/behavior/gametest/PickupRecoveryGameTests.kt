package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.phys.Vec3
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder("samcnpc_pickup_zoo")
@PrefixGameTestTemplate(false)
object PickupRecoveryGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=2000, batch="pickup_hunt_obstruction")
    fun realHuntLootBehindALivingObstacleUsesTheSharedBoundedDetour(h: GameTestHelper) = run(h, false)

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=2000, batch="pickup_farm_obstruction")
    fun realCropLootBehindALivingObstacleUsesTheSameRecoveryWithoutLosingInitialStock(h: GameTestHelper) = run(h, true)

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=1800, batch="pickup_mining_slow")
    fun slowPhysicalMiningPickupKeepsItsDropWaitAndOriginalTaskDeadline(h: GameTestHelper) {
        val arena = CombatGameTestArena(h)
        val base = arena.start
        fun block(x: Int, y: Int, z: Int): NpcBlockPosition {
            val pos = h.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(pos.x,pos.y,pos.z)
        }
        h.setBlock(BlockPos(10,1,0), Blocks.IRON_ORE)
        h.setBlock(BlockPos(0,1,4), Blocks.CHEST)
        val output = h.level.getBlockEntity(h.absolutePos(BlockPos(0,1,4))) as ChestBlockEntity
        var incident = false
        var beganCollection = 0L
        var collectionDuration = 0L
        var previousRemaining = Int.MAX_VALUE
        var originalSpeed = 0.0
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.IRON_PICKAXE))
            arena.give(npc, ItemStack(Items.RAW_IRON,5))
            val at = npc.snapshot()
            val work = MiningWorkOrder(WorkArea(WorkBox(block(10,1,0),block(10,1,0))),
                MiningMethod.EXPOSED, WorkResourceIds(listOf("minecraft:iron_ore")))
            arena.assign(npc, MiningTaskDefinition(at.dimensionId,work,WorkResourceIds(listOf("minecraft:raw_iron")),
                ContainerChoices(listOf(block(0,1,4))),1,MiningCounting.DELIVERED_ITEMS,at.position,
                returnTo=at.position,budget=TaskBudget(1600)))
        }
        arena.observe { npc, record ->
            check(record.primary.remainingTicks <= previousRemaining) { "Collection reset the job deadline" }
            previousRemaining = record.primary.remainingTicks
            val state = checkNotNull(record.primary.mining)
            if (!incident && state.phase == MiningPhase.COLLECT) {
                val drops = h.level.getEntitiesOfClass(ItemEntity::class.java,arena.body.boundingBox.inflate(16.0)).filter { it.item.`is`(Items.RAW_IRON) }
                check(drops.sumOf { it.item.count } == 1) { "No exact native ore drop for slow pickup" }
                val drop = drops.single()
                // Reposition only the existing native drop and body. The reduced real movement
                // speed makes the supplied collection leg last longer than the absent-loot wait.
                drop.setPos(base.x+13.0,base.y,base.z); drop.deltaMovement=Vec3.ZERO
                arena.body.setPos(base.x+7.0,base.y,base.z); arena.body.deltaMovement=Vec3.ZERO
                val speed = checkNotNull(arena.body.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED))
                originalSpeed=speed.baseValue; speed.baseValue=0.1
                incident=true; beganCollection=h.tick
            }
            if (incident && collectionDuration == 0L && h.tick % 40L == 0L) {
                com.mojang.logging.LogUtils.getLogger().info("SLOW_PICKUP_TRACE tick={} body={} velocity={} navigation={} wait={} report={}",
                    h.tick,npc.snapshot().position,arena.body.deltaMovement,npc.snapshot().navigation,state.collectionTicks,record.report())
            }
            if (incident && collectionDuration == 0L && state.phase != MiningPhase.COLLECT) {
                collectionDuration=h.tick-beganCollection
                checkNotNull(arena.body.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)).baseValue=originalSpeed
                check(collectionDuration > MiningTaskState.COLLECTION_TICKS) { "Physical approach did not outlast the drop wait: $collectionDuration" }
                check(state.cargo(record.primary.definition as MiningTaskDefinition) == 1) { "Collector left real ore behind after $collectionDuration ticks: ${record.detail}" }
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED) { record.report() }
                check(incident && collectionDuration > MiningTaskState.COLLECTION_TICKS)
                check((0 until output.containerSize).sumOf { if (output.getItem(it).`is`(Items.RAW_IRON)) output.getItem(it).count else 0 } == 1)
                check(TaskDelivery.inventoryCount(npc,"minecraft:raw_iron") == 5)
                check(h.level.getEntitiesOfClass(ItemEntity::class.java,arena.body.boundingBox.inflate(20.0)).none { it.item.`is`(Items.RAW_IRON) })
                com.mojang.logging.LogUtils.getLogger().info("MINING_SLOW_PICKUP_PROOF physical_collection_ticks={} original_budget_remaining={} delivered=1 protected_stock=5",collectionDuration,previousRemaining)
                arena.succeed(npc,record)
            }
        }
    }

    private fun run(h: GameTestHelper, farming: Boolean) {
        val arena=CombatGameTestArena(h)
        val base=arena.start
        val pig=arena.mob(EntityType.PIG,4.0,1.0)
        val cow=if (farming) null else arena.mob(EntityType.COW,5.0,0.0,4.0)
        fun block(x:Int,y:Int,z:Int):NpcBlockPosition { val p=h.absolutePos(BlockPos(x,y,z));return NpcBlockPosition(p.x,p.y,p.z) }
        h.setBlock(BlockPos(0,1,3),Blocks.CHEST)
        val output=h.level.getBlockEntity(h.absolutePos(BlockPos(0,1,3))) as ChestBlockEntity
        if (farming) {
            h.setBlock(BlockPos(5,0,0),Blocks.FARMLAND)
            h.setBlock(BlockPos(5,1,0),(Blocks.WHEAT as CropBlock).getStateForAge(7))
        }
        val item=if(farming) Items.WHEAT else Items.BEEF
        val id=if(farming) "minecraft:wheat" else "minecraft:beef"
        var displaced=false; var detoured=false; var nativeYield=0; var obstructTicks=0
        val trace=ArrayDeque<String>()
        var original:java.util.UUID?=null; var remaining=1800
        arena.onReady { npc ->
            arena.give(npc,ItemStack(item,3))
            if(!farming) arena.give(npc,ItemStack(Items.IRON_SWORD))
            val destination=ContainerChoices(listOf(block(0,1,3)))
            val definition=if(farming) FarmTaskDefinition(npc.snapshot().dimensionId,
                FarmWorkOrder(WorkArea(WorkBox(block(5,1,0),block(5,1,0))),FarmCrop.WHEAT,FarmMode.HARVEST),
                destination,1,base,returnTo=base,budget=TaskBudget(1800))
            else FoodTaskDefinition(npc.snapshot().dimensionId,
                FoodWorkOrder.Hunt(WorkArea(WorkBox(block(3,1,-2),block(10,3,2))),NpcEntityTypeFilter.of(setOf("minecraft:cow")),1),
                WorkResourceIds(listOf(id)),destination,1,3,base,returnTo=base,budget=TaskBudget(1800))
            arena.assign(npc,definition)
        }
        arena.observe { npc,record ->
            // This actor remains in place; ordinary collision push must not remove the obstacle.
            pig.setPos(base.x+4.0,base.y,base.z+1.0);pig.deltaMovement=Vec3.ZERO
            if(original==null) original=record.id
            check(record.id==original && record.primary.remainingTicks<=remaining)
            remaining=record.primary.remainingTicks
            val collecting=if(farming) record.primary.farming?.phase==FarmPhase.COLLECT else record.primary.food?.phase==FoodPhase.COLLECT
            if(!displaced && collecting) {
                val drops=h.level.getEntitiesOfClass(ItemEntity::class.java,arena.body.boundingBox.inflate(12.0)).filter { it.item.`is`(item) }
                check(drops.isNotEmpty()) { "native operation produced no uncollected item for the obstruction incident" }
                nativeYield=drops.sumOf { it.item.count }
                // Move existing native loot and the collector into the recorded collision geometry.
                // No stack is created, copied, credited or removed by this incident actor.
                for(drop in drops) { drop.setPos(base.x+5.66,base.y,base.z+0.729);drop.deltaMovement=Vec3.ZERO }
                arena.body.setPos(base.x+3.5,base.y,base.z+0.3);arena.body.deltaMovement=Vec3.ZERO
                displaced=true
            }
            if(record.detail.startsWith("local obstacle detour")) detoured=true
            // A finite external displacement keeps the contact point stable across random
            // collision pushes. Core measures the actual lack of movement; its state and
            // Behavior's timers are never injected. Release as soon as recovery starts.
            if(displaced && !detoured && obstructTicks++ < 40) {
                arena.body.setPos(base.x+3.5,base.y,base.z+0.3);arena.body.deltaMovement=Vec3.ZERO
            }
            if(displaced && !record.status.terminal && trace.size<100) trace.add("tick=${h.tick} navigation=${npc.snapshot().navigation} detail=${record.detail}")
            if(record.status.terminal) {
                check(record.status==TaskStatus.COMPLETED) { record.report() }
                check(displaced && detoured) { "incident did not exercise shared obstruction recovery: ${trace.joinToString("\n")}" }
                check(pig.isAlive && pig.health==pig.maxHealth && (cow==null || !cow.isAlive))
                val delivered=(0 until output.containerSize).sumOf { if(output.getItem(it).`is`(item)) output.getItem(it).count else 0 }
                check(delivered==1 && TaskDelivery.inventoryCount(npc,id)==3+nativeYield-1)
                check(h.level.getEntitiesOfClass(ItemEntity::class.java,arena.body.boundingBox.inflate(12.0)).none { it.item.`is`(item) })
                com.mojang.logging.LogUtils.getLogger().info("PICKUP_DETOUR_PROOF farming={} nativeYield={} delivered={} retained={} remaining={}",
                    farming,nativeYield,delivered,TaskDelivery.inventoryCount(npc,id),remaining)
                arena.succeed(npc,record)
            }
        }
    }
}
