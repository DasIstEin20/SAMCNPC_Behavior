package io.samcnpc.behavior.runtime

import io.samcnpc.behavior.model.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class BehaviorActionScopeTest {
    @Test
    fun losingAnyRequiredChannelStopsMovementBeforeTheWinnerRuns() {
        val scope = BehaviorActionScope()
        val npc = MovingBody()
        val move = intent("move", setOf(BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK))
        val look = intent("look", setOf(BehaviorChannel.LOOK))
        scope.prepare(listOf(move), npc)
        scope.facade(move, npc).applyControl(NpcControlInput(1.0F, 0.0F))
        assertNotNull(npc.control)
        scope.prepare(listOf(look), npc) { npc.events.add("release") }
        assertNull(npc.control)
        npc.events.add("winner")
        assertEquals(listOf("start", "release", "stop", "winner"), npc.events)
    }

    @Test
    fun aRetainedProducerKeepsItsLeaseAndGeneration() {
        val scope = BehaviorActionScope()
        val npc = MovingBody()
        val move = intent("move", setOf(BehaviorChannel.MOVEMENT))
        scope.prepare(listOf(move), npc)
        val generation = assertNotNull(scope.execution(move)).generation
        val action = scope.facade(move, npc).applyControl(NpcControlInput(1.0F, 0.0F))
        repeat(10) { scope.prepare(listOf(move), npc) }
        assertEquals(action.actionId, npc.control?.actionId)
        assertEquals(generation, scope.execution(move)?.generation)
        assertEquals(listOf("start"), npc.events)
    }

    @Test
    fun cancellationForgetsSynchronousAndLateCompletionsBeforeAnotherExecution() {
        val scope = BehaviorActionScope()
        val npc = MovingBody()
        val move = intent("move", setOf(BehaviorChannel.MOVEMENT))
        scope.prepare(listOf(move), npc)
        val oldFacade = scope.facade(move, npc)
        val oldId = assertNotNull(oldFacade.applyControl(NpcControlInput(1.0F, 0.0F)).actionId)
        npc.onStop = { scope.completed(NpcActionResult.failed("cancelled", NpcActionCode.CANCELLED, oldId)) }
        val oldGeneration = scope.execution(move)?.generation
        scope.clear(npc)
        scope.prepare(listOf(move), npc)
        assertNotEquals(oldGeneration, scope.execution(move)?.generation)
        val current = scope.facade(move, npc).applyControl(NpcControlInput(1.0F, 0.0F))
        scope.completed(NpcActionResult.succeeded("late", oldId))
        assertNull(scope.takeCompletion(move, BehaviorActionScope.Mechanism.LOCOMOTION))
        assertEquals(NpcActionStatus.REJECTED, oldFacade.applyControl(NpcControlInput.IDLE).status)
        assertEquals(current.actionId, npc.control?.actionId)
        scope.completed(NpcActionResult.succeeded("arrived", current.actionId))
        assertEquals(current.actionId, scope.takeCompletion(move, BehaviorActionScope.Mechanism.LOCOMOTION)?.actionId)
        assertNull(scope.takeCompletion(move, BehaviorActionScope.Mechanism.LOCOMOTION))
    }

    @Test
    fun releaseDoesNotCancelANewerExternalMechanicalAction() {
        val scope = BehaviorActionScope()
        val npc = MovingBody()
        val move = intent("move", setOf(BehaviorChannel.MOVEMENT))
        scope.prepare(listOf(move), npc)
        scope.facade(move, npc).applyControl(NpcControlInput(1.0F, 0.0F))
        val external = npc.applyControl(NpcControlInput(0.0F, 1.0F))
        scope.clear(npc)
        assertEquals(external.actionId, npc.control?.actionId)
        assertFalse("stop" in npc.events)
    }

    @Test
    fun theEmptyDecisionStillReleasesBeforeReturning() {
        val effects = mutableListOf<String>()
        val plan = BehaviorDecisionPlan(emptyList())
        val result = plan.tick(decisionContext(), mutableMapOf(), beforeExecution = {
            assertTrue(it.isEmpty()); effects.add("released")
        }) { error("empty plan executed") }
        assertTrue(result.outcomes.isEmpty())
        assertEquals(listOf("released"), effects)
    }

    private fun intent(id: String, channels: Set<BehaviorChannel>) = ActionIntent(
        "test:$id", 0, "act", 0, 0, CompiledAction("test:$id", { _, _, _ -> error("not invoked") }, channels),
    )

    private class MovingBody : TestNpcFacade() {
        var control: NpcControlState? = null
        var onStop: () -> Unit = {}
        val events = mutableListOf<String>()
        override fun snapshot() = decisionContext().snapshot.copy(control = control)
        override fun applyControl(input: NpcControlInput): NpcActionResult {
            val id = UUID.randomUUID()
            control = NpcControlState(id, input, 200)
            events.add("start")
            return NpcActionResult.accepted("moving", id, NpcActionChannel.LOCOMOTION)
        }
        override fun stopControl(): NpcActionResult {
            control = null
            events.add("stop")
            onStop()
            return NpcActionResult.succeeded("stopped")
        }
    }
}
