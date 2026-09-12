package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.kernel.navigation.PassageYielding
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.*
import kotlin.math.abs

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object PassageYieldGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 750, batch = "bounded_narrow_passage_yield")
    fun twoOpposedRoutesUseTheSideBayThenReachBothOriginalDestinations(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper); val server = helper.level.server
        for (x in -2..16) for (y in 1..3) {
            helper.setBlock(BlockPos(x, y, -1), Blocks.STONE)
            helper.setBlock(BlockPos(x, y, 1), Blocks.STONE)
        }
        for (x in 5..10) for (z in 1..3) for (y in 1..3) {
            helper.setBlock(BlockPos(x, y, z), if (x in 6..9 && z <= 2) Blocks.AIR else Blocks.STONE)
        }
        for (z in -1..1) for (y in 1..3) {
            helper.setBlock(BlockPos(-2, y, z), Blocks.STONE); helper.setBlock(BlockPos(16, y, z), Blocks.STONE)
        }
        arena.body.moveTo(arena.start.x + 3, arena.start.y, arena.start.z)
        val otherBody = checkNotNull(arena.body.type.create(helper.level)) as LivingEntity
        otherBody.moveTo(arena.start.x + 12, arena.start.y, arena.start.z); check(helper.level.addFreshEntity(otherBody))
        var otherAssigned = false; var firstTask: java.util.UUID? = null; var secondTask: java.util.UUID? = null
        var sawYield = false; var sawPhysicalSideStep = false; var yieldingTicks = 0; var clearingTicks = 0
        arena.onReady { npc ->
            arena.assign(npc, NavigateTaskDefinition(npc.snapshot().dimensionId, arena.start.copy(x = arena.start.x + 14),
                speed = 0.7F, budget = TaskBudget(ticks = 700)))
        }
        arena.observe { npc, record ->
            val service = CoreNpcApi.service(server)
            val other = checkNotNull(service.find(otherBody.uuid)?.let(service::runtime))
            if (!otherAssigned && otherBody.onGround()) {
                check(TaskService.assign(server, other, NavigateTaskDefinition(other.snapshot().dimensionId,
                    arena.start.copy(x = arena.start.x + 1), speed = 0.7F, budget = TaskBudget(ticks = 700))).status == NpcActionStatus.SUCCEEDED)
                otherAssigned = true; firstTask = record.id; secondTask = TaskStore.forServer(server).get(other.npcUuid)?.id
            }
            val second = TaskStore.forServer(server).get(other.npcUuid) ?: return@observe
            if (npc.snapshot().gameTime % 10L == 0L) com.mojang.logging.LogUtils.getLogger().info(
                "PASSAGE_PROOF tick={} firstPos={} firstYield={} firstNav={} firstDetail={} secondPos={} secondYield={} secondNav={} secondDetail={}",
                npc.snapshot().gameTime, npc.snapshot().position, PassageYielding.describe(npc.npcUuid), npc.snapshot().navigation,
                record.detail, other.snapshot().position, PassageYielding.describe(other.npcUuid), other.snapshot().navigation, second.detail)
            val count = listOf(npc, other).count { PassageYielding.isYielding(it.npcUuid) }
            check(count <= 1) { "both NPCs yielded to each other" }
            if (count > 0) { sawYield = true; yieldingTicks++ }
            if (listOf(npc, other).any { PassageYielding.isClearing(it.npcUuid) }) clearingTicks++
            sawPhysicalSideStep = sawPhysicalSideStep || abs(arena.body.z - arena.start.z) > 1.0 || abs(otherBody.z - arena.start.z) > 1.0
            check(record.id == firstTask && second.id == secondTask)
            if (record.status.terminal && second.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && second.status == TaskStatus.COMPLETED) {
                    "first=${TaskService.status(server, npc.npcUuid)} second=${TaskService.status(server, other.npcUuid)}"
                }
                check(sawYield && sawPhysicalSideStep && yieldingTicks in 1..120 && clearingTicks in 1..60) { "yield=$sawYield sidestep=$sawPhysicalSideStep ticks=$yieldingTicks clearing=$clearingTicks" }
                check(record.totalFailures == 0 && second.totalFailures == 0)
                check(record.primary.remainingTicks < 700 && second.primary.remainingTicks < 700)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, arena.start.copy(x = arena.start.x + 14)) <= 0.75 * 0.75)
                check(TaskNavigator.distanceSquared(other.snapshot().position, arena.start.copy(x = arena.start.x + 1)) <= 0.75 * 0.75)
                check(helper.getBlockState(BlockPos(7, 1, -1)).`is`(Blocks.STONE))
                com.mojang.logging.LogUtils.getLogger().info("PASSAGE_PROOF_DONE clearingTicks={} totalYieldTicks={} firstRemaining={} secondRemaining={} retries=0",
                    clearingTicks, yieldingTicks, record.primary.remainingTicks, second.primary.remainingTicks)
                otherBody.discard(); arena.succeed(npc, record)
            }
        }
    }
}
