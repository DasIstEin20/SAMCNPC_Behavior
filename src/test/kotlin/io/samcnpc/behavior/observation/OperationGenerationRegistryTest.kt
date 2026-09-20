package io.samcnpc.behavior.observation

import io.samcnpc.behavior.api.OperationGenerations
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class OperationGenerationRegistryTest {
    private val npc = UUID(1, 1)
    private val dimension = "minecraft:overworld"

    @Test fun ticksKeepTheSameIdentityButDimensionLoadAndBackwardClockInvalidateTheBody() {
        val state = OperationGenerationRegistry()
        state.begin()
        val first = capture(state, tick = 20)
        assertEquals(first, capture(state, tick = 21))
        val load = UUID.randomUUID()
        val loaded = capture(state, tick = 22, load = load)
        assertNotEquals(first.body, loaded.body)
        assertEquals(first.serverSession, loaded.serverSession)
        assertEquals(loaded, capture(state, tick = 23, load = load))
        val moved = capture(state, tick = 23, load = load, dimension = "minecraft:the_nether")
        assertNotEquals(loaded.body, moved.body)
        val rewound = capture(state, tick = 1, load = load, dimension = "minecraft:the_nether")
        assertNotEquals(moved.body, rewound.body)
    }

    @Test fun successfulRegistryActivationDoesNotPretendThePhysicalBodyChanged() {
        val state = OperationGenerationRegistry()
        state.begin()
        val before = capture(state)
        state.registryChanged()
        val after = capture(state)
        assertEquals(before.serverSession, after.serverSession)
        assertEquals(before.body, after.body)
        assertNotEquals(before.registry, after.registry)
    }

    @Test fun removalInvalidatesARecreatedBodyEvenWithTheSameUuidDimensionAndLoad() {
        val state = OperationGenerationRegistry()
        state.begin()
        val load = UUID.randomUUID()
        val before = capture(state, load = load)
        state.remove(npc)
        val after = capture(state, load = load)
        assertNotEquals(before.body, after.body)
        assertEquals(before.registry, after.registry)
        assertEquals(before.serverSession, after.serverSession)
    }

    @Test fun stoppedOrNotStartedSessionsRejectCaptureAndRestartChangesEveryIdentity() {
        val state = OperationGenerationRegistry()
        assertEquals(OperationGenerationRegistry.Result.Unavailable(OperationGenerationRegistry.Failure.NOT_STARTED),
            state.capture(npc, dimension, null, 0))
        state.begin()
        val before = capture(state)
        state.clear()
        state.registryChanged()
        assertIs<OperationGenerationRegistry.Result.Unavailable>(state.capture(npc, dimension, null, 0))
        state.begin()
        val after = capture(state)
        assertNotEquals(before.serverSession, after.serverSession)
        assertNotEquals(before.registry, after.registry)
        assertNotEquals(before.body, after.body)
    }

    @Test fun capacityRejectsNewIdentitiesWhileExistingCapturesAndReleasedCapacityStillWork() {
        val state = OperationGenerationRegistry(capacity = 1)
        state.begin()
        val first = capture(state)
        val other = UUID(1, 2)
        assertEquals(OperationGenerationRegistry.Result.Unavailable(OperationGenerationRegistry.Failure.CAPACITY),
            state.capture(other, dimension, null, 0))
        assertEquals(first, capture(state, tick = 1))
        assertEquals(OperationGenerationRegistry.Result.Unavailable(OperationGenerationRegistry.Failure.INVALID_CLOCK),
            state.capture(npc, dimension, null, -1))
        assertEquals(first, capture(state, tick = 1))
        state.remove(npc)
        assertIs<OperationGenerationRegistry.Result.Available>(state.capture(other, dimension, null, 2))
    }

    private fun capture(state: OperationGenerationRegistry, tick: Long = 0, load: UUID? = null,
                        dimension: String = this.dimension): OperationGenerations =
        assertIs<OperationGenerationRegistry.Result.Available>(state.capture(npc, dimension, load, tick)).value
}
