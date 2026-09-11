package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.model.*
import io.samcnpc.behavior.runtime.BehaviorActionScope
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
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
object BehaviorActionLifecycleGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 700, batch = "behavior_unassign")
    fun unassignStopsLumberjackMiningBeforeTheNextBodyTick(helper: GameTestHelper) = miningInterruption(helper, false)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 700, batch = "behavior_reload")
    fun acceptedReloadStopsTheOldMiningActionAndResumesWithFreshObservation(helper: GameTestHelper) = miningInterruption(helper, true)

    private fun miningInterruption(helper: GameTestHelper, reload: Boolean) {
        floor(helper)
        helper.setBlock(BlockPos(1, 1, 1), Blocks.CHEST)
        for (y in 1..3) helper.setBlock(BlockPos(5, y, 3), Blocks.OAK_LOG)
        val body = spawn(helper)
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.WOODEN_AXE))
        var started = false
        var interrupted = false
        helper.onEachTick {
            if (interrupted || !body.onGround()) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!started) {
                check(LumberjackService.start(helper.level.server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
                started = true
            }
            val breaking = npc.snapshot().blockBreak ?: return@onEachTick
            val position = BlockPos(breaking.position.x, breaking.position.y, breaking.position.z)
            check(!helper.level.getBlockState(position).isAir) { "Mining fixture already completed before interruption" }
            interrupted = true
            if (reload) check(BehaviorRuntimeService.reload().accepted)
            else check(BehaviorRuntimeService.assignPacks(helper.level.server, body.uuid, emptyList()).status == NpcActionStatus.SUCCEEDED)
            check(npc.snapshot().blockBreak == null) { "Old mining action survived assignment/reload until a later body tick" }
            helper.runAfterDelay(40) {
                if (reload) {
                    val current = npc.snapshot().blockBreak
                    check(current == null || current.actionId != breaking.actionId) { "Reload reused the cancelled Core action" }
                    check(!helper.level.getBlockState(position).`is`(Blocks.OAK_LOG) || current != null) {
                        "Reload left work asleep instead of re-observing and continuing"
                    }
                } else {
                    check(npc.snapshot().blockBreak == null && npc.snapshot().navigation == null && npc.snapshot().control == null)
                    check(helper.level.getBlockState(position).`is`(Blocks.OAK_LOG)) { "Unassigned NPC continued mining" }
                }
                LumberjackService.cancel(helper.level.server, npc)
                body.discard()
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 160, batch = "behavior_control_release")
    fun losingSelectionReleasesNavigationAndHeldFoodWithoutConsumingIt(helper: GameTestHelper) {
        floor(helper)
        val body = spawn(helper)
        var started = false
        helper.onEachTick {
            if (started || !body.onGround()) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            started = true
            val scope = BehaviorActionScope()
            val intent = ActionIntent("test:release", 0, "act", 0, 0, CompiledAction("test:release",
                { _, _, _ -> error("test invokes explicit Core primitives") }, setOf(BehaviorChannel.MOVEMENT, BehaviorChannel.MAIN_HAND)))
            scope.prepare(listOf(intent), npc)
            val facade = scope.facade(intent, npc)
            val destination = helper.absolutePos(BlockPos(7, 1, 3))
            check(facade.navigateTo(NpcPosition(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5)).status == NpcActionStatus.ACCEPTED)
            check(npc.snapshot().navigation != null)
            body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.GOLDEN_APPLE))
            check(facade.startItemUse(NpcHand.MAIN).status == NpcActionStatus.ACCEPTED)
            check(npc.snapshot().itemUse != null)
            scope.prepare(emptyList(), npc)
            check(npc.snapshot().navigation == null && npc.snapshot().control == null && npc.snapshot().itemUse == null)
            helper.runAfterDelay(50) {
                check(body.mainHandItem.`is`(Items.GOLDEN_APPLE) && body.mainHandItem.count == 1) { "Cancelled use consumed its item" }
                body.discard()
                helper.succeed()
            }
        }
    }

    private fun floor(helper: GameTestHelper) {
        for (x in 0..9) for (z in 0..7) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
    }

    private fun spawn(helper: GameTestHelper): LivingEntity {
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(BlockPos(2, 1, 3))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, -90.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        return body
    }
}
