package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object FarmIrrigationGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1200,batch="farm_irrigation_drop")
    fun actualWheatDriftingBelowFarmlandIsPickedUpFromDryGroundAndDeliveredWithoutSpendingStock(h: GameTestHelper) {
        val arena=CombatGameTestArena(h)
        val crop=h.absolutePos(BlockPos(4,1,0))
        val water=h.absolutePos(BlockPos(5,0,0))
        val recipient=h.absolutePos(BlockPos(0,1,3))
        h.setBlock(BlockPos(5,-1,0),Blocks.STONE)
        h.setBlock(BlockPos(5,0,0),Blocks.WATER)
        h.setBlock(BlockPos(4,0,0),Blocks.FARMLAND)
        h.setBlock(BlockPos(4,1,0),(Blocks.WHEAT as CropBlock).getStateForAge(7))
        h.setBlock(BlockPos(0,1,3),Blocks.CHEST)
        val output=h.level.getBlockEntity(recipient) as ChestBlockEntity
        val harvested=NpcBlockPosition(crop.x,crop.y,crop.z)
        var nativeDrop: UUID?=null
        var confirmedPickup=0
        val listener=object {
            @SubscribeEvent(priority=EventPriority.LOWEST)
            fun harvestCompleted(event: NpcActionCompletedEvent) {
                if(nativeDrop != null || event.handle.npcUuid != arena.body.uuid ||
                    event.result.channel != NpcActionChannel.BLOCK_ACTION || event.result.status != NpcActionStatus.SUCCEEDED) return
                val drops=h.level.getEntitiesOfClass(ItemEntity::class.java,AABB(crop).inflate(1.0))
                    .filter { it.item.`is`(Items.WHEAT) }
                val drop=drops.single()
                check(drop.item.count == 1 && h.level.getBlockState(crop).isAir)
                nativeDrop=drop.uuid
                // Reproduce the saved failing drift using the native harvest stack and pickup delay.
                // Gravity, water buoyancy, approach, pickup and yield accounting remain production code.
                drop.setPos(water.x+0.875,water.y+0.53326458258492,water.z+0.125)
                drop.deltaMovement=Vec3.ZERO
            }
            @SubscribeEvent(priority=EventPriority.LOWEST)
            fun picked(event: NpcItemPickupCompletedEvent) {
                if(event.npcUuid != arena.body.uuid || event.candidate.itemEntityUuid != nativeDrop) return
                check(event.candidate.position.y < crop.y-0.125) { "regression did not exercise the former rejected height" }
                check(!arena.body.isInWater) { "irrigation pickup must use the supported dry approach" }
                confirmedPickup+=event.moved
                MinecraftForge.EVENT_BUS.unregister(this)
            }
        }
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.WHEAT,5))
            MinecraftForge.EVENT_BUS.register(listener)
            val work=FarmWorkOrder(WorkArea(WorkBox(harvested,harvested)),FarmCrop.WHEAT,FarmMode.HARVEST)
            val position=npc.snapshot().position
            arena.assign(npc,FarmTaskDefinition(npc.snapshot().dimensionId,work,
                ContainerChoices(listOf(NpcBlockPosition(recipient.x,recipient.y,recipient.z))),1,position,
                returnTo=position,budget=TaskBudget(1100)))
        }
        arena.observe { npc,record -> if(record.status.terminal) {
            MinecraftForge.EVENT_BUS.unregister(listener)
            check(record.status == TaskStatus.COMPLETED) { TaskService.status(h.level.server,npc.npcUuid).orEmpty() }
            check(nativeDrop != null && confirmedPickup == 1 && h.level.getEntity(checkNotNull(nativeDrop)) == null)
            val state=checkNotNull(record.primary.farming)
            check(state.harvested == mapOf(harvested to 1) && state.pickups["minecraft:wheat"] == 1)
            val row=state.resources.physical.entries.getValue("minecraft:wheat")
            check(row.valid() && row.gathered == 1 && row.delivered == 1 && row.retained == 5)
            check((0 until output.containerSize).sumOf { if(output.getItem(it).`is`(Items.WHEAT)) output.getItem(it).count else 0 } == 1)
            check(TaskDelivery.inventoryCount(npc,"minecraft:wheat") == 5 && h.level.getBlockState(crop).isAir)
            arena.succeed(npc,record)
        } }
    }
}
