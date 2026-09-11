package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.NavigateTaskDefinition
import io.samcnpc.behavior.task.TaskService
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object LegacyWorkPreservationGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 150, batch = "legacy_work_preserved")
    fun unknownWorkVersionKeepsTheActualNpcIdleAndCannotBeOverwrittenByEitherAssignment(helper: GameTestHelper) {
        for (x in 0..7) for (z in 0..6) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        helper.setBlock(BlockPos(5, 1, 3), Blocks.OAK_LOG)
        helper.setBlock(BlockPos(1, 1, 1), Blocks.CHEST)
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(BlockPos(2, 1, 3))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, 0.0F, 0.0F)
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.OAK_LOG, 8))
        check(helper.level.addFreshEntity(body))
        var started = false
        helper.onEachTick {
            if (started || !body.onGround()) return@onEachTick
            val server = helper.level.server
            val service = CoreNpcApi.service(server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            started = true
            val previous = LumberjackDemoStore.forServer(server)
            val future = CompoundTag().apply {
                putInt("version", 99)
                put("jobs", ListTag().apply {
                    add(CompoundTag().apply { putUUID("npcUuid", body.uuid); putString("futureObligation", "do not erase scaffold receipt") })
                })
            }
            val preserved = LumberjackDemoStore.load(future)
            server.overworld().dataStorage.set("samcnpc_behavior_lumberjack_demo", preserved)
            check(BehaviorRuntimeService.assignPacks(server, body.uuid, listOf(LumberjackService.PACK_ID)).status == NpcActionStatus.SUCCEEDED)
            check(LumberjackService.start(server, npc, npc.worldView()).status == NpcActionStatus.REJECTED)
            check(TaskService.assign(server, npc, NavigateTaskDefinition(npc.snapshot().dimensionId, NpcPosition(feet.x + 2.5, feet.y.toDouble(), feet.z + 0.5))).status == NpcActionStatus.REJECTED)
            val start = npc.snapshot().position
            helper.runAfterDelay(30) {
                check(npc.snapshot().navigation == null && npc.snapshot().control == null && npc.snapshot().blockBreak == null)
                check(npc.snapshot().position == start && body.mainHandItem.count == 8)
                check(helper.getBlockState(BlockPos(5, 1, 3)).`is`(Blocks.OAK_LOG))
                check(BehaviorRuntimeService.diagnostic(body.uuid)?.taskStatus?.contains("original data preserved") == true)
                check(preserved.save(CompoundTag()) == future)
                check(BehaviorRuntimeService.assignPacks(server, body.uuid, emptyList()).status == NpcActionStatus.SUCCEEDED)
                server.overworld().dataStorage.set("samcnpc_behavior_lumberjack_demo", previous)
                body.discard()
                helper.succeed()
            }
        }
    }
}
