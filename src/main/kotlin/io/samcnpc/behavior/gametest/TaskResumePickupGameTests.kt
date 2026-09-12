package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object TaskResumePickupGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1000, batch = "task_only_reload_then_passive_pickup")
    fun aPickupAfterCoherentTaskOnlyResumeIsNewLiveWorkRatherThanACorruptBodySave(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper); val server = helper.level.server
        helper.setBlock(BlockPos(16, 1, 0), Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(16, 1, 0))) as ChestBlockEntity
        val wood = WoodSelection(listOf("samcnpc:oak"))
        arena.onReady { npc ->
            check(npc.inventoryLoadSnapshot() == null)
            arena.give(npc, ItemStack(Items.IRON_AXE))
            arena.assign(npc, LumberjackTaskDefinition(npc.snapshot().dimensionId,
                WorkArea(WorkBox(absolute(helper, 3, 1, 2), absolute(helper, 4, 3, 3))), wood,
                absolute(helper, 16, 1, 0), 1, budget = TaskBudget(ticks = 900), version = 2))
            check(TaskService.pause(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
            val saved = TaskStore.forServer(server).save(CompoundTag())
            check(BehaviorRuntimeService.reload().accepted)
            server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
            check(TaskService.resume(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
            check(TaskStore.forServer(server).get(npc.npcUuid)?.primary?.lumberjack?.resources?.mustReconcileLoad == false)
            val drop = ItemEntity(helper.level, arena.body.x, arena.body.y, arena.body.z, ItemStack(Items.OAK_LOG))
            drop.setNoPickUpDelay(); check(helper.level.addFreshEntity(drop))
            check(TaskDelivery.inventoryCount(npc, "minecraft:oak_log") == 0)
        }
        var sawActualPickup = false
        arena.observe { npc, record ->
            val resources = checkNotNull(record.primary.lumberjack).resources
            sawActualPickup = sawActualPickup || resources.entries["minecraft:oak_log"]?.gathered == 1
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && sawActualPickup) { TaskService.status(server, npc.npcUuid).orEmpty() }
                check(resources.delivered(wood) == 1 && !resources.uncertain)
                check((0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.OAK_LOG)) chest.getItem(it).count else 0 } == 1)
                check(TaskDelivery.inventoryCount(npc, "minecraft:oak_log") == 0)
                arena.succeed(npc, record)
            }
        }
    }
    private fun absolute(helper: GameTestHelper, x: Int, y: Int, z: Int): NpcBlockPosition {
        val p = helper.absolutePos(BlockPos(x, y, z)); return NpcBlockPosition(p.x, p.y, p.z)
    }
}
