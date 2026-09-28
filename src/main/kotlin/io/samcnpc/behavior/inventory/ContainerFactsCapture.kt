package io.samcnpc.behavior.inventory

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

/** At most sixteen authorized endpoints, sampled on demand at most once per second. */
internal class ContainerFactsCapture {
    private var queries: Map<ContainerFactRequest, NpcStockQuery> = emptyMap()
    private var tick: Long? = null
    private var observations: Map<ContainerFactRequest, NpcBlockContainerObservation> = emptyMap()
    fun capture(requested: Map<ContainerFactRequest, NpcStockQuery>, world: NpcWorldView, gameTime: Long): Map<ContainerFactRequest, NpcBlockContainerObservation> {
        require(requested.size <= 16)
        val previousTick = tick
        if (requested == queries && previousTick != null && gameTime in previousTick until previousTick + 20) return observations
        queries = java.util.Map.copyOf(requested); tick = gameTime
        val current = linkedMapOf<ContainerFactRequest, NpcBlockContainerObservation>()
        for ((key, query) in requested) {
            if (!BehaviorPlanning.admit(world, 130, PlanningKind.CONTAINER)) continue
            val observed = VisibleContainerFacts.observe(world, query) ?: continue
            current[key] = observed.copy(slots = java.util.List.copyOf(observed.slots))
        }
        observations = java.util.Map.copyOf(current)
        return observations
    }
}
