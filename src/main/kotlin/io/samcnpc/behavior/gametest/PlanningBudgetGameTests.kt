package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.behavior.task.*
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object PlanningBudgetGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 650, batch = "aggregate_planning_pressure")
    fun sixtyFourRealScannersGetTurnsWhileCargoMovementTransferAndGravityContinue(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper); val server = helper.level.server
        for (x in 1..25) for (z in 1..25) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        helper.setBlock(BlockPos(16, 1, -2), Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(16, 1, -2))) as ChestBlockEntity
        val destination = absolute(helper, 16, 1, -2)
        val bodies = (0 until 64).map { index ->
            val body = checkNotNull(arena.body.type.create(helper.level)) as LivingEntity
            body.moveTo(arena.start.x + 2 + index % 8 * 3, arena.start.y + if (index == 63) 3.0 else 0.0,
                arena.start.z + 2 + index / 8 * 3)
            check(helper.level.addFreshEntity(body)); body
        }
        val fallingStart = bodies.last().y
        var assigned = false; var hadPressure = false; var hadGravity = false
        val seen = mutableSetOf<java.util.UUID>()
        val beforeDeferred = BehaviorPlanning.statistics().deferredSlices
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.OAK_LOG, 8))
            arena.assign(npc, DeliveryTaskDefinition(npc.snapshot().dimensionId, destination, "minecraft:oak_log", 8,
                budget = TaskBudget(ticks = 600), version = 2, anchor = arena.start))
        }
        arena.observe { npc, record ->
            val service = CoreNpcApi.service(server)
            val workers = bodies.map { checkNotNull(service.find(it.uuid)?.let(service::runtime)) }
            if (!assigned) {
                for (worker in workers) {
                    val drop = ItemEntity(helper.level, worker.snapshot().position.x, worker.snapshot().position.y,
                        worker.snapshot().position.z, ItemStack(Items.IRON_AXE))
                    drop.setNoPickUpDelay(); check(helper.level.addFreshEntity(drop))
                    check(worker.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED)
                    val definition = LumberjackTaskDefinition(worker.snapshot().dimensionId,
                        WorkArea(WorkBox(absolute(helper, 1, 1, 1), absolute(helper, 25, 27, 25))),
                        WoodSelection(listOf("samcnpc:oak")), destination, 1, budget = TaskBudget(ticks = 600), version = 2)
                    check(TaskService.assign(server, worker, definition).status == NpcActionStatus.SUCCEEDED)
                    // Start this pressure fixture at the durable search phase. Supply gameplay
                    // has separate world tests; every block read below uses the real runtime.
                    checkNotNull(TaskStore.forServer(server).get(worker.npcUuid)?.primary?.lumberjack).job.phase = LumberjackDemoPhase.SEARCH_WOOD
                }
                assigned = true
            }
            for (worker in workers) {
                val work = checkNotNull(TaskStore.forServer(server).get(worker.npcUuid))
                if (checkNotNull(work.primary.lumberjack).job.scanCursor > 0) seen.add(worker.npcUuid)
                check(work.totalFailures == 0) { "waiting for planning consumed a retry: ${work.report()}" }
            }
            val stats = BehaviorPlanning.statistics()
            check(stats.reserved <= stats.limit && stats.consumed <= stats.reserved && stats.peakReserved <= stats.limit)
            hadPressure = hadPressure || stats.deferredSlices > beforeDeferred && stats.waiting > 0
            hadGravity = hadGravity || bodies.last().y < fallingStart - 2.5 && bodies.last().onGround()
            if (record.status.terminal) check(record.status == TaskStatus.COMPLETED) { record.report().toString() }
            if (seen.size == 64 && record.status == TaskStatus.COMPLETED) {
                check(hadPressure && hadGravity && stats.peakObservedQueries > 0)
                check((0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.OAK_LOG)) chest.getItem(it).count else 0 } == 8)
                check(record.primary.transport?.ledger?.delivered == 8 && record.totalFailures == 0)
                for (worker in workers) check(TaskService.cancel(server, worker.npcUuid).status == NpcActionStatus.SUCCEEDED)
                bodies.forEach { it.discard() }
                arena.succeed(npc, record)
            }
        }
    }
    private fun absolute(helper: GameTestHelper, x: Int, y: Int, z: Int): NpcBlockPosition {
        val p = helper.absolutePos(BlockPos(x, y, z)); return NpcBlockPosition(p.x, p.y, p.z)
    }
}
