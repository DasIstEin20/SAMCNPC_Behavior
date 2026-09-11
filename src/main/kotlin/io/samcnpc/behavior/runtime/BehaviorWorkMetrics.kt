package io.samcnpc.behavior.runtime

import io.samcnpc.core.api.*
import java.util.UUID

enum class BehaviorObservationKind {
    ENTITY, ENTITIES, BLOCK, CONTAINER, STANDING_SPACE, RAYCAST, SNAPSHOT, INVENTORY, EQUIPMENT, KNOWLEDGE,
}

data class BehaviorWorkStatistics(
    val decisions: Long,
    val evaluatedRules: Long,
    val eligibleIntents: Long,
    val executedIntents: Long,
    val observations: Map<BehaviorObservationKind, Long>,
    val lastObservations: Map<BehaviorObservationKind, Long>,
    val timedDecisions: Long,
    val totalDecisionNanos: Long,
    val lastDecisionNanos: Long?,
)

/** Fixed-size counters only in the hot path. Formatting and map copies happen on diagnostic reads. */
internal class BehaviorWorkMetrics(
    private val measureTime: Boolean = false,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val totals = LongArray(BehaviorObservationKind.entries.size)
    private val last = LongArray(BehaviorObservationKind.entries.size)
    private var decisions = 0L
    private var evaluatedRules = 0L
    private var eligibleIntents = 0L
    private var executedIntents = 0L
    private var totalNanos = 0L
    private var lastNanos = 0L

    fun begin(): Long {
        last.fill(0L)
        return if (measureTime) nanoTime() else 0L
    }

    fun observed(kind: BehaviorObservationKind) {
        totals[kind.ordinal]++
        last[kind.ordinal]++
    }

    fun finish(decision: BehaviorDecisionResult?, started: Long) {
        decisions++
        if (decision != null) {
            evaluatedRules += decision.evaluatedRules
            eligibleIntents += decision.eligibleIntents
            executedIntents += decision.outcomes.size
        }
        if (measureTime) {
            lastNanos = (nanoTime() - started).coerceAtLeast(0L)
            totalNanos += lastNanos
        }
    }

    fun snapshot(): BehaviorWorkStatistics = BehaviorWorkStatistics(
        decisions, evaluatedRules, eligibleIntents, executedIntents,
        copyCounts(totals), copyCounts(last), if (measureTime) decisions else 0L,
        totalNanos, if (measureTime) lastNanos else null,
    )

    private fun copyCounts(values: LongArray): Map<BehaviorObservationKind, Long> {
        val copy = java.util.EnumMap<BehaviorObservationKind, Long>(BehaviorObservationKind::class.java)
        for (kind in BehaviorObservationKind.entries) copy[kind] = values[kind.ordinal]
        return java.util.Collections.unmodifiableMap(copy)
    }
}

/** No observation values are cached: an action may change the world before the next read. */
internal class MeasuredWorldView(
    private val delegate: NpcWorldView,
    private val metrics: BehaviorWorkMetrics,
) : NpcWorldView {
    override val dimensionId: String get() = delegate.dimensionId

    override fun observeEntity(uuid: UUID): NpcEntityObservation? {
        metrics.observed(BehaviorObservationKind.ENTITY)
        return delegate.observeEntity(uuid)
    }

    override fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation> {
        metrics.observed(BehaviorObservationKind.ENTITIES)
        return delegate.queryEntities(query)
    }

    override fun observeBlock(position: NpcBlockPosition): NpcBlockObservation? {
        metrics.observed(BehaviorObservationKind.BLOCK)
        return delegate.observeBlock(position)
    }

    override fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation? {
        metrics.observed(BehaviorObservationKind.CONTAINER)
        return delegate.observeBlockContainer(position)
    }

    override fun observeStandingSpace(feet: NpcPosition): NpcStandingSpaceObservation? {
        metrics.observed(BehaviorObservationKind.STANDING_SPACE)
        return delegate.observeStandingSpace(feet)
    }

    override fun raycast(request: NpcRaycastRequest): NpcRaycastResult {
        metrics.observed(BehaviorObservationKind.RAYCAST)
        return delegate.raycast(request)
    }
}

internal class MeasuredNpcFacade(
    private val delegate: NpcFacade,
    private val metrics: BehaviorWorkMetrics,
) : NpcFacade by delegate {
    override fun snapshot(): NpcSnapshot {
        metrics.observed(BehaviorObservationKind.SNAPSHOT)
        return delegate.snapshot()
    }

    override fun inventoryContents(): List<NpcInventoryEntry> {
        metrics.observed(BehaviorObservationKind.INVENTORY)
        return delegate.inventoryContents()
    }

    override fun equipmentContents(): NpcEquipmentSnapshot {
        metrics.observed(BehaviorObservationKind.EQUIPMENT)
        return delegate.equipmentContents()
    }

    override fun equipmentKnowledge(): NpcEquipmentKnowledge {
        metrics.observed(BehaviorObservationKind.KNOWLEDGE)
        return delegate.equipmentKnowledge()
    }

    override fun worldView(): NpcWorldView = MeasuredWorldView(delegate.worldView(), metrics)
}
