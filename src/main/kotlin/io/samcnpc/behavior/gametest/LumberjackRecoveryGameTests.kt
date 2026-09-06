package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarResultCode
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarSession
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarState
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.phys.Vec3
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object LumberjackRecoveryGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1100, batch = "lumberjack_nested_recovery")
    fun accessLeafPreservesTheSuspendedMaterialRecovery(helper: GameTestHelper) {
        for (x in 0..8) for (z in 0..6) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val base = helper.absolutePos(BlockPos(3, 1, 3))
        val upper = helper.absolutePos(BlockPos(3, 8, 3))
        val leaf = helper.absolutePos(BlockPos(2, 1, 3))
        helper.setBlock(BlockPos(3, 1, 3), Blocks.OAK_LOG)
        helper.setBlock(BlockPos(3, 8, 3), Blocks.OAK_LOG)
        helper.setBlock(BlockPos(2, 1, 3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true))
        helper.setBlock(BlockPos(0, 1, 1), Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(0, 1, 1))) as Container
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(BlockPos(1, 1, 3))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, 0.0F, 0.0F)
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_AXE))
        check(helper.level.addFreshEntity(body))
        var started = false
        var sawNestedAccess = false
        val supportPositions = mutableSetOf<NpcBlockPosition>()
        val baseTarget = NpcBlockPosition(base.x, base.y, base.z)
        val upperTarget = NpcBlockPosition(upper.x, upper.y, upper.z)
        helper.onEachTick {
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!started && body.onGround()) {
                check(LumberjackService.start(helper.level.server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
                val job = checkNotNull(LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid))
                job.phase = LumberjackDemoPhase.TRAVEL_TO_LOG
                job.scanCursor = 2500
                job.trunkBasePosition = baseTarget
                job.targetPosition = baseTarget
                job.blockedLogPosition = upperTarget
                job.scaffoldMaterialRecovery = true
                // Already-felled wood becomes real task loot after the inventory baseline.
                val drop = ItemEntity(helper.level, body.x, body.y, body.z, ItemStack(Items.OAK_LOG, 6))
                drop.deltaMovement = Vec3.ZERO
                drop.setNoPickUpDelay()
                check(helper.level.addFreshEntity(drop))
                started = true
                helper.runAfterDelay(1000) {
                    check(sawNestedAccess) { "The fixture did not exercise nested leaf/material work" }
                    check(listOf(base, upper, leaf).all { helper.level.getBlockState(it).isAir }) { "A deferred work block remained" }
                    check(supportPositions.isNotEmpty() && supportPositions.all { helper.level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir }) {
                        "Nested recovery abandoned its supports: $supportPositions"
                    }
                    val deposited = (0 until chest.containerSize).sumOf { slot ->
                        val stack = chest.getItem(slot)
                        if (stack.`is`(Items.OAK_LOG)) stack.count else 0
                    }
                    check(deposited == 8) { "Expected two original plus six recovered logs: $deposited" }
                    check(npc.inventoryContents().none { it.stack.itemId == "minecraft:oak_log" })
                    check(LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid) == null)
                    body.discard()
                    helper.succeed()
                }
            }
            val current = LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid)
            current?.pillarSession?.placedPositions?.let(supportPositions::addAll)
            if (current?.accessReturnTarget != null) {
                sawNestedAccess = true
                check(current.accessReturnTarget == baseTarget && current.blockedLogPosition == upperTarget) {
                    "The access leaf overwrote the material prerequisite: $current"
                }
            }
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 900, batch = "lumberjack_column_recovery")
    fun woodSupportsInTrunkColumnAreCleanedBeforeAnotherTask(helper: GameTestHelper) {
        for (x in 0..6) for (z in 0..6) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val base = helper.absolutePos(BlockPos(3, 1, 3))
        val target = helper.absolutePos(BlockPos(3, 8, 3))
        helper.setBlock(BlockPos(3, 8, 3), Blocks.OAK_LOG)
        val supports = (1..4).map { y ->
            helper.setBlock(BlockPos(3, y, 3), Blocks.OAK_LOG)
            val position = helper.absolutePos(BlockPos(3, y, 3))
            NpcBlockPosition(position.x, position.y, position.z)
        }
        helper.setBlock(BlockPos(1, 1, 3), Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(1, 1, 3))) as Container
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        body.moveTo(base.x + 0.5, base.y + 4.0, base.z + 0.5, 0.0F, 0.0F)
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_AXE))
        check(helper.level.addFreshEntity(body))
        var started = false
        helper.onEachTick {
            if (started || !body.onGround()) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            check(LumberjackService.start(helper.level.server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
            val job = checkNotNull(LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid))
            // Stage a legitimate in-flight job, as on a world reload: four real recovered logs
            // form a scaffold in the cleared trunk column, and one original upper log remains.
            job.phase = LumberjackDemoPhase.BREAK_LOG
            job.scanCursor = 2500
            job.trunkBasePosition = NpcBlockPosition(base.x, base.y, base.z)
            job.targetPosition = NpcBlockPosition(target.x, target.y, target.z)
            job.miningStance = NpcBlockPosition(base.x, base.y + 4, base.z)
            job.scaffoldMaterialRecovery = true
            job.pillarSession = TemporaryPillarSession(UUID.randomUUID(), job.targetPosition ?: error("target missing"),
                TemporaryPillarState.RESTORE_TASK_ITEM, 0, 1, 1, "minecraft:oak_log", 4, 0,
                TemporaryPillarResultCode.PILLAR_TARGET_REACHED, null, null, supports.toMutableList())
            started = true
            helper.runAfterDelay(800) {
                val remaining = supports.filter { !helper.level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir }
                val deposited = (0 until chest.containerSize).sumOf { slot ->
                    val stack = chest.getItem(slot)
                    if (stack.`is`(Items.OAK_LOG)) stack.count else 0
                }
                check(helper.level.getBlockState(target).isAir && remaining.isEmpty()) { "Original log/scaffold remained: $remaining" }
                check(deposited == 5) { "Scaffold and original tree wood must be conserved: deposited=$deposited" }
                check(npc.inventoryContents().none { it.stack.itemId == "minecraft:oak_log" }) { "Late or undeposited wood" }
                check(LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid) == null) { "Recovery did not finish" }
                body.discard()
                helper.succeed()
            }
        }
    }
}
