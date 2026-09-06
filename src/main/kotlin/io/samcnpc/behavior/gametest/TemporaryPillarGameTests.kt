package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarKernel
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarResultCode
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarSession
import io.samcnpc.behavior.kernel.elevation.TemporaryPillarState
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object TemporaryPillarGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 250, batch = "pillar_pickup")
    fun placementSurvivesContactPickup(helper: GameTestHelper) = exercisePlacement(helper, injectPickup = true)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 250, batch = "pillar_empty_stack")
    fun lastMaterialBlockIsAccountedFor(helper: GameTestHelper) = exercisePlacement(helper, injectPickup = false)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 250, batch = "pillar_edge")
    fun edgePickupReacquiresLegalSupport(helper: GameTestHelper) = exercisePlacement(helper, injectPickup = false, atEdge = true)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 100, batch = "pillar_water_cleanup")
    fun immersedCleanupYieldsWithoutBreakingSupports(helper: GameTestHelper) = exerciseIncompleteCleanup(helper, underWater = true)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 100, batch = "pillar_cleanup_deadline")
    fun airborneCleanupHonorsItsPersistedDeadline(helper: GameTestHelper) = exerciseIncompleteCleanup(helper, underWater = false)

    private fun exerciseIncompleteCleanup(helper: GameTestHelper, underWater: Boolean) {
        for (x in 1..5) for (z in 1..5) {
            helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
            if (underWater) helper.setBlock(BlockPos(x, 1, z), Blocks.WATER)
        }
        val supportBlock = helper.absolutePos(BlockPos(3, 0, 3))
        val support = NpcBlockPosition(supportBlock.x, supportBlock.y, supportBlock.z)
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        body.moveTo(support.x + 0.5, support.y + if (underWater) 1.0 else 8.0, support.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        // Resume an existing recorded scaffold. Physical state comes from the real entity;
        // only the already elapsed persisted cleanup budget is staged by the fixture.
        val session = TemporaryPillarSession(
            taskId = UUID.randomUUID(), targetPosition = support, state = TemporaryPillarState.DESCEND_BREAK,
            originalSelectedHotbarSlot = 0, materialOriginalSlot = 0, materialActiveSlot = 0,
            materialItemId = "minecraft:stone", estimatedLevels = 1, retries = 0,
            lastResult = TemporaryPillarResultCode.PILLAR_CLEANUP_INCOMPLETE,
            currentPlacement = null, expectedMaterialCountAfterPlacement = null,
            placedPositions = mutableListOf(support),
            cleanupTicks = if (underWater) 0 else TemporaryPillarKernel.MAX_CLEANUP_TICKS - 1,
        )
        var sawWait = false
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            val snapshot = npc.snapshot()
            if (underWater && !snapshot.inWater) return@onEachTick
            if (!underWater) check(!snapshot.onGround && !snapshot.inWater) { "Deadline fixture must still be falling in dry air" }
            val progress = TemporaryPillarKernel.tickCleanup(npc, npc.worldView(), session)
            if (!underWater && !sawWait) {
                check(progress is TemporaryPillarKernel.PillarProgress.Running) { "Cleanup expired before its deadline: $progress" }
                sawWait = true
                return@onEachTick
            }
            check(progress == TemporaryPillarKernel.PillarProgress.CleanupIncomplete) { "Unsafe cleanup did not yield: $progress" }
            check(session.lastResult == if (underWater) TemporaryPillarResultCode.PILLAR_UNSAFE_ENVIRONMENT else TemporaryPillarResultCode.PILLAR_CLEANUP_INCOMPLETE)
            check(session.placedPositions == listOf(support)) { "Incomplete cleanup lost its support record" }
            check(npc.snapshot().blockBreak == null) { "Incomplete cleanup left a destructive action running" }
            for (x in 1..5) for (z in 1..5) {
                check(helper.level.getBlockState(helper.absolutePos(BlockPos(x, 0, z))).`is`(Blocks.STONE)) {
                    "Unsafe cleanup modified a recorded or unrelated block"
                }
            }
            finished = true
            body.discard()
            helper.succeed()
        }
    }

    private fun exercisePlacement(helper: GameTestHelper, injectPickup: Boolean, atEdge: Boolean = false) {
        for (x in 1..5) for (z in 1..5) helper.setBlock(BlockPos(x, 0, z), if (atEdge) Blocks.AIR else Blocks.STONE)
        helper.setBlock(BlockPos(3, 0, 3), Blocks.STONE)
        val target = helper.absolutePos(BlockPos(3, 8, 3))
        helper.setBlock(BlockPos(3, 8, 3), Blocks.OAK_LOG)
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(BlockPos(3, 1, 3))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + if (atEdge) 1.1 else 0.5, 0.0F, 0.0F)
        val materialCount = if (atEdge) 4 else 3
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.OAK_LOG, materialCount))
        check(helper.level.addFreshEntity(body))
        val server = helper.level.server
        var session: io.samcnpc.behavior.kernel.elevation.TemporaryPillarSession? = null
        var injected = false
        var finished = false
        helper.onEachTick {
            if (!finished && body.onGround() && session == null) {
                val service = CoreNpcApi.service(server)
                val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
                val begun = TemporaryPillarKernel.begin(npc, npc.worldView(), NpcBlockPosition(target.x, target.y, target.z))
                check(begun is TemporaryPillarKernel.PillarBeginResult.Started) { "Pillar fixture could not start: $begun" }
                session = begun.session
            }
            val current = session ?: return@onEachTick
            if (finished) return@onEachTick
            val service = CoreNpcApi.service(server)
            val npc = checkNotNull(service.find(body.uuid)?.let(service::runtime))
            val progress = TemporaryPillarKernel.tick(npc, npc.worldView(), current)
            check(progress !is TemporaryPillarKernel.PillarProgress.Failed) {
                "Real placement/pickup failed: $progress session=$current inventory=${npc.inventoryContents().filter { !it.stack.isEmpty }}"
            }
            val placement = current.currentPlacement
            if (injectPickup && !injected && placement != null && npc.worldView().observeBlock(placement)?.isSolid == true) {
                // Contact pickup happens between two Behavior ticks, exactly like a falling log
                // landing beside the worker. Do not simulate it by changing an inventory count.
                val drop = ItemEntity(helper.level, body.x, body.y, body.z, ItemStack(Items.OAK_LOG))
                drop.deltaMovement = Vec3.ZERO
                drop.setNoPickUpDelay()
                check(helper.level.addFreshEntity(drop))
                injected = true
            }
            if (progress == TemporaryPillarKernel.PillarProgress.TargetReached) {
                finished = true
                check(!injectPickup || injected) { "Fixture never injected its real contact pickup" }
                check(current.placedPositions.size == materialCount) { "Expected $materialCount real placed levels: $current" }
                val carried = npc.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:oak_log") it.stack.count else 0 }
                check(carried == if (injectPickup) 1 else 0) { "Placement did not conserve real material: carried=$carried session=$current" }
                for (position in current.placedPositions) {
                    check(helper.level.getBlockState(BlockPos(position.x, position.y, position.z)).`is`(Blocks.OAK_LOG))
                }
                body.discard()
                helper.succeed()
            }
        }
    }
}
