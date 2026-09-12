package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
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
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.abs

/** Reproduces the flat-ground jump and scaffold handoffs found in the actual dev client. */
@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object LumberjackStumpGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2100, batch = "lumberjack_flat_short")
    fun dismantlesSupportBeforeTheRetainedStump(helper: GameTestHelper) = exercise(helper, 6, true)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2100, batch = "lumberjack_flat_tall_dirt")
    fun landsOnStumpAndFinishesTallTrunkWithDirt(helper: GameTestHelper) = exercise(helper, 9, true)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2100, batch = "lumberjack_flat_tall_wood")
    fun landsOnStumpAndFinishesTallTrunkWithEarnedWood(helper: GameTestHelper) = exercise(helper, 9, false)

    private fun exercise(helper: GameTestHelper, height: Int, suppliedDirt: Boolean) {
        for (x in 0..11) for (z in 0..6) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val base = helper.absolutePos(BlockPos(8, 1, 3))
        val trunks = (1..height).map { y ->
            helper.setBlock(BlockPos(8, y, 3), Blocks.OAK_LOG)
            helper.absolutePos(BlockPos(8, y, 3))
        }
        val leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
        for (x in 6..10) for (z in 1..5) for (y in height - 2..height) {
            if (x != 8 || z != 3) helper.setBlock(BlockPos(x, y, z), leaves)
        }
        helper.setBlock(BlockPos(1, 1, 5), Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(1, 1, 5))) as Container
        chest.setItem(0, ItemStack(Items.IRON_AXE))
        chest.setItem(1, ItemStack(Items.IRON_SHOVEL))
        if (suppliedDirt) chest.setItem(2, ItemStack(Items.DIRT, 16))
        chest.setChanged()
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level))
        val spawn = helper.absolutePos(BlockPos(1, 1, 3))
        body.moveTo(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        var started = false
        var finished = false
        var elapsed = 0
        var quietTicks = 0
        var stumpLandings = 0
        var previousPhase: LumberjackDemoPhase? = null
        val supports = mutableSetOf<NpcBlockPosition>()
        helper.onEachTick {
            if (finished) return@onEachTick
            check(++elapsed < 2000) { "Flat-ground lumberjack timed out: ${LumberjackService.status(helper.level.server, body.uuid)}" }
            val service = CoreNpcApi.service(helper.level.server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!started && body.onGround()) {
                check(LumberjackService.start(helper.level.server, npc, npc.worldView()).status == NpcActionStatus.SUCCEEDED)
                started = true
            }
            if (!started) return@onEachTick
            val snapshot = npc.snapshot()
            val job = LumberjackDemoStore.forServer(helper.level.server).jobFor(body.uuid)
            job?.pillarSession?.placedPositions?.let(supports::addAll)
            if (job?.phase == LumberjackDemoPhase.COLLECT_TREE_DROPS && job.pickupTicks % 60 == 59) {
                com.mojang.logging.LogUtils.getLogger().info("STUMP_COLLECTION height={} dirt={} elapsed={} position={} status={}",
                    height, suppliedDirt, job.pickupTicks, snapshot.position,
                    LumberjackService.status(helper.level.server, body.uuid))
            }
            if (previousPhase == LumberjackDemoPhase.CLIMB_TRUNK && job?.phase == LumberjackDemoPhase.BREAK_LOG) {
                check(snapshot.onGround && abs(snapshot.position.y - base.y - 1.0) <= 0.05 &&
                    abs(snapshot.position.x - base.x - 0.5) <= 0.75 && abs(snapshot.position.z - base.z - 0.5) <= 0.75) {
                    "Jump handed work to mining before a real stump landing: $snapshot"
                }
                stumpLandings++
            }
            previousPhase = job?.phase
            if (job != null) {
                val activeSupports = job.pillarSession?.placedPositions.orEmpty()
                val upperLogsGone = trunks.drop(1).all { position ->
                    helper.level.getBlockState(position).isAir || NpcBlockPosition(position.x, position.y, position.z) in activeSupports
                }
                check(!(upperLogsGone && activeSupports.isNotEmpty() && job.phase == LumberjackDemoPhase.TRAVEL_TO_LOG &&
                    job.targetPosition == NpcBlockPosition(base.x, base.y, base.z))) {
                    "Navigating away from the scaffold before dismantling it above the retained stump"
                }
                return@onEachTick
            }
            check(stumpLandings > 0) { "Fixture did not exercise a real stump landing" }
            check(trunks.all { helper.level.getBlockState(it).isAir }) { "Unfinished original trunk: $trunks" }
            check(supports.isNotEmpty() && supports.all { helper.level.getBlockState(BlockPos(it.x, it.y, it.z)).isAir }) {
                "Abandoned temporary supports: $supports"
            }
            val deposited = (0 until chest.containerSize).sumOf { slot ->
                val stack = chest.getItem(slot)
                if (stack.`is`(Items.OAK_LOG)) stack.count else 0
            }
            check(deposited == height) { "Wood lost or duplicated: expected=$height deposited=$deposited" }
            check(npc.inventoryContents().none { it.stack.itemId == "minecraft:oak_log" }) { "Undeposited or late-picked-up wood" }
            if (++quietTicks >= 40) {
                finished = true
                body.discard()
                helper.succeed()
            }
        }
    }
}
