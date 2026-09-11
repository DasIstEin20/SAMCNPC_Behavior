package io.samcnpc.behavior.runtime

import com.google.gson.JsonObject
import io.samcnpc.behavior.model.BehaviorChannel
import io.samcnpc.behavior.model.CompiledAction
import io.samcnpc.behavior.model.CompiledPack
import io.samcnpc.behavior.model.CompiledRule
import io.samcnpc.behavior.model.ConditionExpression
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BehaviorDecisionPlanTest {
    @Test
    fun higherLookIntentPreventsEverySideEffectOfRegisteredMovement() {
        val moveArgs = JsonObject().apply { addProperty("speed", 1.0); addProperty("stopDistance", 3.0) }
        val move = registeredAction("samcnpc:move_to_target", moveArgs)
        val look = registeredAction("samcnpc:look_at_target")
        val plan = BehaviorDecisionPlan(listOf(
            pack("samcnpc:move", listOf(move), priority = 10),
            pack("samcnpc:look", listOf(look), priority = 11),
        ))
        val effects = mutableListOf<String>()
        plan.tick(decisionContext(), mutableMapOf()) { intent ->
            effects.add(intent.action.actionId)
            NpcActionResult.succeeded("executed")
        }
        assertEquals(setOf(BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK), move.channels)
        assertEquals(listOf("samcnpc:look_at_target"), effects)
    }

    @Test
    fun everyPackPermutationGivesTheSameWinnersAndAtomicChannelReservations() {
        val look = action("test:look", BehaviorChannel.LOOK)
        val composite = action("test:composite", BehaviorChannel.LOOK, BehaviorChannel.MOVEMENT)
        val move = action("test:move", BehaviorChannel.MOVEMENT)
        val packs = listOf(pack("test:alpha", listOf(look)), pack("test:beta", listOf(composite)), pack("test:gamma", listOf(move)))
        for (a in packs.indices) for (b in packs.indices) for (c in packs.indices) {
            if (setOf(a, b, c).size != 3) continue
            val effects = mutableListOf<String>()
            BehaviorDecisionPlan(listOf(packs[a], packs[b], packs[c])).tick(decisionContext(), mutableMapOf()) {
                effects.add(it.action.actionId)
                NpcActionResult.succeeded("executed")
            }
            assertEquals(listOf("test:look", "test:move"), effects, "permutation $a $b $c")
        }
    }

    @Test
    fun conditionsAreEvaluatedOnceBeforeAnyActionsOfTheTick() {
        var evaluations = 0
        val condition = ConditionExpression.Test("test:read") { evaluations++; true }
        val rule = CompiledRule("rule", 0, 0, condition, listOf(
            action("test:look", BehaviorChannel.LOOK), action("test:inventory", BehaviorChannel.INVENTORY),
        ))
        val compiled = CompiledPack("test:plan", "", 0, BehaviorChannel.entries.toSet(), listOf(rule))
        var executions = 0
        BehaviorDecisionPlan(listOf(compiled)).tick(decisionContext(), mutableMapOf()) {
            assertEquals(1, evaluations)
            executions++
            NpcActionResult.running("continuing")
        }
        assertEquals(2, executions)
        assertEquals(1, evaluations)
    }

    @Test
    fun onlyAcceptedOrSucceededActionsConsumeCooldown() {
        for (status in NpcActionStatus.entries) {
            val plan = BehaviorDecisionPlan(listOf(pack("test:cooldown", listOf(action("test:work", BehaviorChannel.MAIN_HAND)), cooldown = 5)))
            val cooldowns = mutableMapOf<String, Long>()
            val result = plan.tick(decisionContext(), cooldowns) { NpcActionResult(status, "outcome") }
            val consumes = status == NpcActionStatus.ACCEPTED || status == NpcActionStatus.SUCCEEDED
            assertEquals(if (consumes) 105L else null, cooldowns["test:cooldown/rule"], status.name)
            var nextExecutions = 0
            plan.tick(decisionContext(tick = 101), cooldowns) { nextExecutions++; NpcActionResult.running("continuing") }
            assertEquals(if (consumes) 0 else 1, nextExecutions, status.name)
            if (status in listOf(NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED)) {
                assertTrue(result.lastProblem?.contains("outcome") == true)
            } else {
                assertEquals(null, result.lastProblem)
            }
        }
    }

    @Test
    fun aRunningContinuationDoesNotExtendAnExpiredCooldown() {
        val plan = BehaviorDecisionPlan(listOf(pack("test:work", listOf(action("test:work", BehaviorChannel.MAIN_HAND)), cooldown = 5)))
        val cooldowns = mutableMapOf<String, Long>()
        plan.tick(decisionContext(), cooldowns) { NpcActionResult.accepted("started") }
        for (tick in 105L..110L) {
            val result = plan.tick(decisionContext(tick), cooldowns) { NpcActionResult.running("in progress") }
            assertEquals(1, result.selectedLabels.size)
            assertEquals(105L, cooldowns["test:work/rule"])
        }
    }

    @Test
    fun oneAcceptedSiblingStartsTheRuleCooldownAndKeepsTheFailureDiagnostic() {
        val plan = BehaviorDecisionPlan(listOf(pack("test:work", listOf(
            action("test:look", BehaviorChannel.LOOK), action("test:inventory", BehaviorChannel.INVENTORY),
        ), cooldown = 5)))
        val cooldowns = mutableMapOf<String, Long>()
        val result = plan.tick(decisionContext(), cooldowns) {
            if (it.actionIndex == 0) NpcActionResult.accepted("started") else NpcActionResult.rejected("container gone")
        }
        assertEquals(105L, cooldowns["test:work/rule"])
        assertTrue(result.lastProblem?.contains("container gone") == true)
        val next = plan.tick(decisionContext(101), cooldowns) { error("cooldown should suppress both siblings") }
        assertTrue(next.selectedLabels.isEmpty())
    }

    @Test
    fun falseRulesNeverExecuteOrConsumeCooldown() {
        val rule = CompiledRule("rule", 0, 5, ConditionExpression.Test("test:false") { false },
            listOf(action("test:look", BehaviorChannel.LOOK)))
        val plan = BehaviorDecisionPlan(listOf(CompiledPack("test:false", "", 0, setOf(BehaviorChannel.LOOK), listOf(rule))))
        val cooldowns = mutableMapOf<String, Long>()
        val result = plan.tick(decisionContext(), cooldowns) { error("false condition executed") }
        assertFalse(result.selectedLabels.isNotEmpty())
        assertTrue(cooldowns.isEmpty())
    }

    private fun action(id: String, vararg channels: BehaviorChannel) =
        CompiledAction(id, { _, _, _ -> error("unit executor must observe only selected intents") }, channels.toSet())

    private fun pack(id: String, actions: List<CompiledAction>, priority: Int = 0, cooldown: Int = 0) =
        CompiledPack(id, "", 0, BehaviorChannel.entries.toSet(), listOf(
            CompiledRule("rule", priority, cooldown, ConditionExpression.Test("test:always") { true }, actions),
        ))
}
