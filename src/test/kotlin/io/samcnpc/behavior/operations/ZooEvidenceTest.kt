package io.samcnpc.behavior.operations

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class ZooEvidenceTest {
    private fun sample() = ZooTaskSample(UUID(0, 1), UUID(0, 2), 600, 1, true, 1)

    @Test fun rejectsIdentityReplacementAndDeadlineRenewalAfterInterruption() {
        val oracle = ZooTaskOracle(sample())
        oracle.observe(sample().copy(remainingTicks = 550, frames = 2))
        assertFailsWith<IllegalStateException> { oracle.observe(sample().copy(remainingTicks = 551)) }
        assertFailsWith<IllegalStateException> { oracle.observe(sample().copy(taskId = UUID(0, 3), remainingTicks = 550)) }
        assertFailsWith<IllegalStateException> { oracle.observe(sample().copy(primaryId = UUID(0, 3), remainingTicks = 550)) }
        oracle.observe(sample().copy(remainingTicks = 500))
    }

    @Test fun detectsRegionFrameAndCleanupLeaks() {
        val oracle = ZooTaskOracle(sample())
        assertFailsWith<IllegalStateException> { oracle.observe(sample().copy(inBounds = false)) }
        assertFailsWith<IllegalStateException> { oracle.observe(sample().copy(frames = 4)) }
        assertFailsWith<IllegalStateException> { oracle.observe(sample(), released = true) }
        oracle.observe(sample().copy(liveControls = 0), released = true)
    }

    @Test fun accountsForExternalRemovalButRejectsDuplicateOrLostCargo() {
        ZooResourceOracle.conserve(8, 7, externalDelta = -1)
        ZooResourceOracle.conserve(10, 13, produced = 5, consumed = 2)
        assertFailsWith<IllegalStateException> { ZooResourceOracle.conserve(8, 8, externalDelta = -1) }
        assertFailsWith<IllegalStateException> { ZooResourceOracle.conserve(8, 6, externalDelta = -1) }
        assertFailsWith<ArithmeticException> { ZooResourceOracle.conserve(Long.MAX_VALUE, 0, produced = 1) }
    }

    @Test fun inputsTriggerAtSemanticPhaseOnceAndPreserveTheirRecordedValues() {
        val parameters = mutableMapOf("block" to "ore")
        val trace = ZooIncidents(42, listOf(ZooIncident("replace", "selected", parameters)))
        parameters["block"] = "changed input"
        assertNull(trace.pending("moving"))
        val input = assertNotNull(trace.pending("selected"))
        assertEquals("ore", input.parameters["block"])
        val before = mutableMapOf("world" to "ore")
        trace.record(input, 25, before, mapOf("world" to "air"))
        before.clear()
        assertEquals("ore", trace.evidence.single().before["world"])
        assertTrue(trace.complete)
        assertNull(trace.pending("selected"))
        assertFailsWith<IllegalStateException> { trace.record(input, 26, emptyMap(), emptyMap()) }
    }
}
