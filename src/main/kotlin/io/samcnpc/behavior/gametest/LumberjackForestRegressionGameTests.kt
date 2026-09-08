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
object LumberjackForestRegressionGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1300, batch = "forest_obscured_pillar")
    fun obscuredLogUsesItsExistingPillarAndConservesWood(helper: GameTestHelper) {
        floor(helper)
        val target = helper.absolutePos(BlockPos(6, 6, 3))
        val base = helper.absolutePos(BlockPos(6, 1, 3))
        helper.level.setBlock(target, Blocks.SPRUCE_LOG.defaultBlockState(), 3)
        val leaves = listOf(BlockPos(4, 6, 5), BlockPos(5, 6, 4))
        for (leaf in leaves) helper.setBlock(leaf, Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true))
        val chest = chest(helper, BlockPos(1, 1, 5))
        val body = body(helper, BlockPos(3, 1, 6), 0.688732342522395, 0.346635944064356)
        var started = false
        var finished = false
        var sawScaffoldAccess = false
        var quietTicks = 0
        val supports = mutableSetOf<NpcBlockPosition>()
        val pillarTasks = mutableSetOf<UUID>()
        helper.onEachTick {
            if (finished) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!started && body.onGround()) {
                check(LumberjackService.start(helper.level.server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
                val job = checkNotNull(LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid))
                job.phase = LumberjackDemoPhase.COLLECT_LOG_DROP
                job.scanCursor = 2500
                job.trunkBasePosition = base.npcPosition()
                job.targetPosition = base.npcPosition()
                job.blockedLogPosition = target.npcPosition()
                job.scaffoldMaterialRecovery = true
                job.scaffoldRecoveryAttempts = 1
                giveRealDrop(helper, body, 4)
                started = true
            }
            if (!started) return@onEachTick
            val job = LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid)
            job?.pillarSession?.let { session ->
                supports.addAll(session.placedPositions)
                pillarTasks.add(session.taskId)
                if (job.accessReturnTarget != null && session.placedPositions.isNotEmpty()) sawScaffoldAccess = true
            }
            check(pillarTasks.size <= 2) { "Rebuilt the same blocked-view pillar: ${LumberjackService.status(helper.level.server, body.uuid)}" }
            if (job != null) return@onEachTick
            check(helper.level.getBlockState(target).isAir) { "The supplied upper log was abandoned" }
            check(sawScaffoldAccess) { "The fixture never resolved foliage from its elevated work stance" }
            check(supports.isNotEmpty() && supports.all { helper.level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir }) { "Abandoned scaffold: $supports" }
            check(woodCount(chest) == 5) { "Expected the four recovered logs plus the target log, got ${woodCount(chest)}" }
            check(npc.inventoryContents().none { it.stack.itemId == "minecraft:spruce_log" })
            if (++quietTicks >= 40) {
                finished = true
                body.discard()
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 900, batch = "forest_recovery_budget")
    fun exhaustedRecoveryCleansAndDepositsWithoutRebuilding(helper: GameTestHelper) {
        floor(helper)
        val base = helper.absolutePos(BlockPos(5, 1, 5))
        val target = helper.absolutePos(BlockPos(5, 9, 5))
        helper.level.setBlock(target, Blocks.SPRUCE_LOG.defaultBlockState(), 3)
        val placed = (1..4).map { y ->
            helper.setBlock(BlockPos(5, y, 5), Blocks.SPRUCE_LOG)
            helper.absolutePos(BlockPos(5, y, 5)).npcPosition()
        }
        val chest = chest(helper, BlockPos(1, 1, 5))
        val body = body(helper, BlockPos(5, 5, 5))
        val originalTask = UUID.randomUUID()
        var started = false
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!started && body.onGround()) {
                check(LumberjackService.start(helper.level.server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
                val job = checkNotNull(LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid))
                job.phase = LumberjackDemoPhase.PILLAR_CLEANUP
                job.scanCursor = 2500
                job.trunkBasePosition = base.npcPosition()
                job.targetPosition = target.npcPosition()
                job.scaffoldMaterialRecovery = true
                job.scaffoldRecoveryAttempts = 3
                job.pillarSession = TemporaryPillarSession(originalTask, target.npcPosition(), TemporaryPillarState.DESCEND_BREAK,
                    0, 1, 1, "minecraft:spruce_log", 4, 0, TemporaryPillarResultCode.PILLAR_NO_MATERIAL,
                    null, null, placed.toMutableList())
                started = true
            }
            if (!started) return@onEachTick
            val job = LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid)
            check(job?.pillarSession == null || job.pillarSession?.taskId == originalTask) { "Exhausted recovery started another pillar" }
            if (job != null) return@onEachTick
            check(helper.level.getBlockState(target).`is`(Blocks.SPRUCE_LOG)) { "Exhausted task incorrectly resumed cutting" }
            check(placed.all { helper.level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir })
            check(woodCount(chest) == 4) { "Cleanup lost its recovered wood: ${woodCount(chest)}" }
            finished = true
            body.discard()
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 700, batch = "forest_chest_return")
    fun returnToChestClearsOnlyBlockingRouteFoliage(helper: GameTestHelper) {
        floor(helper)
        for (x in 0..11) for (y in 1..3) for (z in listOf(2, 4)) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val leaves = (1..2).map { y ->
            val position = BlockPos(4, y, 3)
            helper.setBlock(position, Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true))
            helper.absolutePos(position)
        }
        val chest = chest(helper, BlockPos(10, 1, 3))
        val body = body(helper, BlockPos(2, 1, 3))
        var started = false
        var finished = false
        var sawAccess = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!started && body.onGround()) {
                check(LumberjackService.start(helper.level.server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
                val job = checkNotNull(LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid))
                job.phase = LumberjackDemoPhase.RETURN_TO_CHEST
                job.scanCursor = 2500
                giveRealDrop(helper, body, 5)
                started = true
            }
            if (!started) return@onEachTick
            val job = LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid)
            if (job?.chestAccessTarget != null) sawAccess = true
            if (job != null) return@onEachTick
            check(sawAccess && leaves.all { helper.level.getBlockState(it).isAir }) { "Chest return did not resolve its collision corridor" }
            check(woodCount(chest) == 5) { "The NPC did not deposit its gathered wood" }
            for (x in 0..11) for (y in 1..3) for (z in listOf(2, 4)) {
                check(helper.level.getBlockState(helper.absolutePos(BlockPos(x, y, z))).`is`(Blocks.STONE)) { "Route clearance broke unrelated terrain" }
            }
            finished = true
            body.discard()
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 600, batch = "forest_head_intersection")
    fun cutsIntersectingTrunkAndDepositsItsWoodOnTheChestRoute(helper: GameTestHelper) {
        floor(helper)
        val chest = chest(helper, BlockPos(1, 1, 1))
        val body = body(helper, BlockPos(8, 1, 6), 0.7, 0.333568890972458)
        val intersectingLog = helper.absolutePos(BlockPos(8, 2, 6))
        // Reproduce a block that appeared around an existing body, as can happen when a tree
        // grows. Normal navigation kept jumping inside this head-level trunk in the live save.
        helper.level.setBlock(intersectingLog, Blocks.SPRUCE_LOG.defaultBlockState(), 3)
        helper.setBlock(BlockPos(8, 2, 4), Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true))
        var started = false
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!started && body.onGround()) {
                check(body.boundingBox.intersects(net.minecraft.world.phys.AABB(intersectingLog))) { "Fixture lost its initial body/log intersection" }
                check(LumberjackService.start(helper.level.server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
                val job = checkNotNull(LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid))
                job.scanCursor = 2500
                giveRealDrop(helper, body, 5)
                // Exercise the saved-position decision before the next vanilla entity tick
                // can push the deliberately intersecting test body out of its fixture.
                val initial = LumberjackService.tick(helper.level.server, npc, npc.worldView())
                check(job.chestAccessTarget == intersectingLog.npcPosition()) {
                    "Intersecting trunk was not selected: eye=${npc.snapshot().eyePosition} log=$intersectingLog action=$initial target=${job.chestAccessTarget}"
                }
                started = true
            }
            if (!started || LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid) != null) return@onEachTick
            check(woodCount(chest) == 6) { "Chest run must deposit the carried wood and the blocking trunk's real drop: count=${woodCount(chest)} position=${npc.snapshot().position}" }
            check(helper.level.getBlockState(intersectingLog).isAir) { "The blocking trunk was not cut" }
            finished = true
            body.discard()
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 700, batch = "forest_hidden_route_leaf")
    fun clearsVisibleHeadLeafBeforeTheHiddenFootLeaf(helper: GameTestHelper) {
        floor(helper)
        for (x in 0..11) for (y in 1..3) for (z in listOf(2, 4)) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        // Keep jumping over the lower leaf impossible after the upper one is cleared; both
        // cells must be removed to traverse this two-block-high passage.
        for (x in 0..11) helper.setBlock(BlockPos(x, 3, 3), Blocks.STONE)
        val lower = helper.absolutePos(BlockPos(4, 1, 3))
        val upper = lower.above()
        val leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
        helper.level.setBlock(lower, leaves, 3)
        helper.level.setBlock(upper, leaves, 3)
        val chest = chest(helper, BlockPos(10, 1, 3))
        val body = body(helper, BlockPos(3, 1, 3), 0.7)
        var started = false
        var finished = false
        var sawHeadClearance = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!started && body.onGround()) {
                val ray = helper.level.clip(net.minecraft.world.level.ClipContext(body.eyePosition, Vec3.atCenterOf(lower),
                    net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, body))
                check(ray.blockPos == upper) { "Fixture does not hide the foot-level leaf behind the head-level leaf" }
                check(LumberjackService.start(helper.level.server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
                checkNotNull(LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid)).scanCursor = 2500
                giveRealDrop(helper, body, 5)
                started = true
            }
            if (!started) return@onEachTick
            val job = LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid)
            if (job?.chestAccessTarget == upper.npcPosition()) sawHeadClearance = true
            if (job != null) return@onEachTick
            check(sawHeadClearance && helper.level.getBlockState(lower).isAir && helper.level.getBlockState(upper).isAir) {
                "Did not clear the first visible foliage before its hidden neighbor: sawHead=$sawHeadClearance lower=${helper.level.getBlockState(lower)} upper=${helper.level.getBlockState(upper)}"
            }
            check(woodCount(chest) == 5) { "Hidden route foliage aborted the chest run" }
            for (x in 0..11) check(helper.level.getBlockState(helper.absolutePos(BlockPos(x, 3, 3))).`is`(Blocks.STONE)) { "Cleared unrelated ceiling terrain" }
            finished = true
            body.discard()
            helper.succeed()
        }
    }

    private fun floor(helper: GameTestHelper) {
        for (x in 0..11) for (z in 0..9) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
    }

    private fun chest(helper: GameTestHelper, position: BlockPos): Container {
        helper.setBlock(position, Blocks.CHEST)
        return checkNotNull(helper.level.getBlockEntity(helper.absolutePos(position)) as? Container)
    }

    private fun body(helper: GameTestHelper, position: BlockPos, offsetX: Double = 0.5, offsetZ: Double = 0.5): LivingEntity {
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(position)
        body.moveTo(feet.x + offsetX, feet.y.toDouble(), feet.z + offsetZ, 0.0F, 0.0F)
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.NETHERITE_AXE))
        check(helper.level.addFreshEntity(body))
        return body
    }

    private fun giveRealDrop(helper: GameTestHelper, body: LivingEntity, count: Int) {
        val drop = ItemEntity(helper.level, body.x, body.y, body.z, ItemStack(Items.SPRUCE_LOG, count))
        drop.deltaMovement = Vec3.ZERO
        drop.setNoPickUpDelay()
        check(helper.level.addFreshEntity(drop))
    }

    private fun woodCount(container: Container): Int = (0 until container.containerSize).sumOf { slot ->
        val stack = container.getItem(slot)
        if (stack.`is`(Items.SPRUCE_LOG)) stack.count else 0
    }

    private fun BlockPos.npcPosition() = NpcBlockPosition(x, y, z)
}
