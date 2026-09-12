package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.*
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object FarmCompletionGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=900,batch="farm_immediate_cancel")
    fun immediateCancellationAtCropCompletionRetainsActualRemovalBeforeAnotherDecision(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val server=helper.level.server
        helper.setBlock(BlockPos(4,0,0),Blocks.FARMLAND); helper.setBlock(BlockPos(4,1,0),(Blocks.WHEAT as CropBlock).getStateForAge(7))
        helper.setBlock(BlockPos(0,1,3),Blocks.CHEST)
        val p=helper.absolutePos(BlockPos(4,1,0)); val target=NpcBlockPosition(p.x,p.y,p.z)
        val c=helper.absolutePos(BlockPos(0,1,3)); val recipient=NpcBlockPosition(c.x,c.y,c.z)
        var cancelled=false; var problem: String?=null
        val listener=object {
            @SubscribeEvent(priority=EventPriority.LOWEST)
            fun farmCropCompleted(event: NpcActionCompletedEvent) {
                if (event.handle.npcUuid != arena.body.uuid || event.result.channel != NpcActionChannel.BLOCK_ACTION || event.result.status != NpcActionStatus.SUCCEEDED || cancelled) return
                val state=TaskStore.forServer(server).get(arena.body.uuid)?.primary?.farming
                if (state?.harvested?.get(target) != 1 || state.phase != FarmPhase.COLLECT) problem="crop completion was left transient until another behavior decision"
                cancelled=true
                if (TaskService.cancel(server,arena.body.uuid).status != NpcActionStatus.SUCCEEDED) problem="immediate crop cancellation failed"
            }
        }
        arena.onReady { npc ->
            MinecraftForge.EVENT_BUS.register(listener)
            arena.assign(npc,FarmTaskDefinition(npc.snapshot().dimensionId,FarmWorkOrder(WorkArea(WorkBox(target,target)),FarmCrop.WHEAT,FarmMode.HARVEST),
                ContainerChoices(listOf(recipient)),1,npc.snapshot().position,budget=TaskBudget(700)))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            MinecraftForge.EVENT_BUS.unregister(listener)
            check(problem == null) { checkNotNull(problem) }
            check(cancelled && record.status == TaskStatus.CANCELLED && record.reason == TaskReason.USER_CANCELLED) { record.detail }
            val restored=TaskCodec.read(TaskCodec.write(record))
            check(restored.primary.farming?.harvested == mapOf(target to 1) && helper.getBlockState(BlockPos(4,1,0)).isAir)
            check(restored.primary.farming?.planted?.isEmpty() == true)
            arena.succeed(npc,record)
        } }
    }
}
