package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.*
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object MiningCompletionGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=500,batch="mining_immediate_cancel")
    fun immediateCancellationAfterCoreCompletionRetainsRemovalAndConsumedTool(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val server=helper.level.server
        helper.setBlock(BlockPos(4,1,0),Blocks.IRON_ORE); helper.setBlock(BlockPos(0,1,3),Blocks.CHEST)
        val p=helper.absolutePos(BlockPos(4,1,0)); val target=NpcBlockPosition(p.x,p.y,p.z)
        val c=helper.absolutePos(BlockPos(0,1,3)); val recipient=NpcBlockPosition(c.x,c.y,c.z)
        var cancelled=false
        val listener=object {
            @SubscribeEvent(priority=EventPriority.LOWEST)
            fun completed(event: NpcActionCompletedEvent) {
                if (event.handle.npcUuid != arena.body.uuid || event.result.channel != NpcActionChannel.BLOCK_ACTION || event.result.status != NpcActionStatus.SUCCEEDED || cancelled) return
                val record=checkNotNull(TaskStore.forServer(server).get(arena.body.uuid))
                val state=checkNotNull(record.primary.mining)
                check(state.selection.removed[target] == "minecraft:iron_ore" && state.phase == MiningPhase.COLLECT) { "Core completion was left transient until another behavior decision" }
                check(state.resources.physical.entries["minecraft:iron_pickaxe"]?.consumed == 1 && state.resources.physical.entries["minecraft:iron_pickaxe"]?.lost == 0) { "actual broken tool was not accounted as consumption" }
                check(TaskService.cancel(server,arena.body.uuid).status == NpcActionStatus.SUCCEEDED)
                cancelled=true
            }
        }
        arena.onReady { npc ->
            val tool=ItemStack(Items.IRON_PICKAXE); tool.damageValue=tool.maxDamage-1; arena.give(npc,tool)
            MinecraftForge.EVENT_BUS.register(listener)
            val work=MiningWorkOrder(WorkArea(WorkBox(target,target)),MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:iron_ore")))
            arena.assign(npc,MiningTaskDefinition(npc.snapshot().dimensionId,work,WorkResourceIds(listOf("minecraft:raw_iron")),ContainerChoices(listOf(recipient)),1,
                MiningCounting.REMOVED_RESOURCE_BLOCKS,npc.snapshot().position))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            MinecraftForge.EVENT_BUS.unregister(listener)
            check(cancelled && record.status == TaskStatus.CANCELLED && record.reason == TaskReason.USER_CANCELLED) { record.detail }
            val restored=TaskCodec.read(TaskCodec.write(record))
            check(restored.primary.mining?.selection?.removed == mapOf(target to "minecraft:iron_ore"))
            check(helper.getBlockState(BlockPos(4,1,0)).isAir)
            check(TaskDelivery.inventoryCount(npc,"minecraft:iron_pickaxe") == 0)
            arena.succeed(npc,record)
        } }
    }
}
