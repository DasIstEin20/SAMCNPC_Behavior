package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcPosition
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object TaskFrameInvariantGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 60, batch = "frame_invariant_tick")
    fun unrelatedStateIsStoppedBeforeTheNextWorldTick(helper: GameTestHelper) = exercise(helper, false)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 60, batch = "frame_invariant_dispatch")
    fun directDispatchRejectsAnUnrelatedStateBeforeNavigation(helper: GameTestHelper) = exercise(helper, true)

    private fun exercise(helper: GameTestHelper, direct: Boolean) {
        val arena = CombatGameTestArena(helper)
        val server = helper.level.server
        arena.onReady { npc ->
            val dimension = npc.snapshot().dimensionId
            arena.assign(npc, NavigateTaskDefinition(dimension,
                NpcPosition(arena.start.x + 8, arena.start.y, arena.start.z)))
            val record = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
            record.primary.combat = checkNotNull(CombatTaskState.forDefinition(
                AttackTaskDefinition(dimension, UUID(0, 77), arena.start)))
            if (direct) {
                check(TaskService.executeSelected(server, npc, npc.worldView()).status == NpcActionStatus.FAILED)
            }
        }
        arena.observe { npc, record ->
            if (!record.status.terminal) return@observe
            check(record.status == TaskStatus.FAILED && record.reason == TaskReason.STATE_MISMATCH)
            check(record.detail.contains("unexpected combat"))
            check(npc.snapshot().navigation == null && npc.snapshot().control == null)
            check(TaskNavigator.distanceSquared(arena.start, npc.snapshot().position) < 0.0001)
            check(BehaviorRuntimeService.assignedPacks(server, npc.npcUuid).isEmpty())
            check(record.primary.combat != null) { "invalid evidence was erased" }
            arena.close()
            helper.succeed()
        }
    }
}
