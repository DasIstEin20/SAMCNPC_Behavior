package io.samcnpc.behavior.observation

import io.samcnpc.behavior.api.OperationGenerations
import java.util.UUID

/** Pure bounded lifecycle bookkeeping. Its containing runtime owns server-thread access. */
internal class OperationGenerationRegistry(
    private val capacity: Int = 4096,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    private data class Body(val id: UUID, val dimension: String, val load: UUID?, var lastTick: Long)
    private val bodies = mutableMapOf<UUID, Body>()
    private var session: UUID? = null
    private var registry: UUID? = null
    init { require(capacity in 1..4096) }

    fun begin() {
        bodies.clear()
        session = newId()
        registry = newId()
    }

    fun registryChanged() {
        if (session != null) registry = newId()
    }

    fun remove(npcUuid: UUID) { bodies.remove(npcUuid) }

    fun clear() {
        bodies.clear()
        session = null
        registry = null
    }

    fun capture(npcUuid: UUID, dimension: String, load: UUID?, tick: Long): Result {
        val sessionId = session ?: return Result.Unavailable(Failure.NOT_STARTED)
        val registryId = registry ?: return Result.Unavailable(Failure.NOT_STARTED)
        if (tick < 0) return Result.Unavailable(Failure.INVALID_CLOCK)
        val old = bodies[npcUuid]
        if (old == null && bodies.size == capacity) return Result.Unavailable(Failure.CAPACITY)
        val body = if (old == null || old.dimension != dimension || old.load != load || tick < old.lastTick) {
            Body(newId(), dimension, load, tick).also { bodies[npcUuid] = it }
        } else {
            old.lastTick = tick
            old
        }
        return Result.Available(OperationGenerations(sessionId, registryId, body.id))
    }

    enum class Failure { NOT_STARTED, INVALID_CLOCK, CAPACITY }
    sealed interface Result {
        data class Available(val value: OperationGenerations) : Result
        data class Unavailable(val reason: Failure) : Result
    }
}
