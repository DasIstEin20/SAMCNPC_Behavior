package io.samcnpc.behavior.runtime

import io.samcnpc.behavior.inventory.LocalTaskFacts
import io.samcnpc.behavior.model.BehaviorReadContext
import io.samcnpc.behavior.registry.BehaviorDefinitions
import io.samcnpc.core.api.NpcActionResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.zip.ZipInputStream

class GuardianForesterGraphTest {
    private fun pack() = ZipInputStream(checkNotNull(javaClass.getResourceAsStream("/studio/guardian_forester.zip"))).use { zip ->
        var entry = zip.nextEntry
        while (entry != null && !entry.name.startsWith("behaviors/")) entry = zip.nextEntry
        checkNotNull(entry)
        val compiled = BehaviorDefinitions.compiler.compile("guardian-forester-actual-export", zip.readBytes().toString(Charsets.UTF_8))
        assertTrue(compiled.report.accepted, compiled.report.toString())
        checkNotNull(compiled.pack)
    }

    private fun winners(context: BehaviorReadContext): List<String> {
        val selected = mutableListOf<String>()
        BehaviorDecisionPlan(listOf(pack())).tick(context, mutableMapOf()) {
            selected.add(it.ruleId)
            NpcActionResult.succeeded("record selected intent without a world mutation")
        }
        return selected
    }

    @Test fun manualPauseSuppressesEveryWorkAndPreparationBranch() {
        assertEquals(listOf("a01_respect_manual_pause"), winners(decisionContext().copy(
            localTaskFacts = LocalTaskFacts("PAUSED", 3, null), taskReady = true,
            taskInventoryRequested = true, taskInventoryReady = true, taskCombatReady = true, taskReactionReady = true)))
    }

    @Test fun unknownContainerStillAdvancesTheBoundedObservationTask() {
        assertEquals(listOf("c08_approach_unobserved_container"), winners(decisionContext().copy(
            localTaskFacts = LocalTaskFacts("RUNNING", 3, null), taskInventoryReady = true)))
    }

    @Test fun noTaskIsCreatedByIdleOrFailedRules() {
        assertEquals(listOf("f03_wait_when_summoner_unavailable"), winners(decisionContext()))
        assertEquals(listOf("f03_wait_when_summoner_unavailable"), winners(decisionContext().copy(
            localTaskFacts = LocalTaskFacts("FAILED", 0, "MISSING_RESOURCE"))))
    }

    @Test fun safetyAndBoundedRetryHaveDistinctChannelsAndPriorities() {
        assertEquals(listOf("a04_emergency_hold_without_summoner"), winners(decisionContext(health = 0.20)))
        assertEquals(listOf("e03_wait_through_bounded_retry_backoff"), winners(decisionContext().copy(
            localTaskFacts = LocalTaskFacts("WAITING", 2, "NO_PROGRESS"))))
        assertEquals(listOf("c01_begin_authorized_task_reaction"), winners(decisionContext().copy(
            localTaskFacts = LocalTaskFacts("RUNNING", 2, null), taskReactionReady = true, taskInventoryRequested = true)))
    }
}
