package io.samcnpc.behavior.inventory

import io.samcnpc.core.api.*

/** Unknown access is not empty stock. Callers must separately authorize the supplied endpoint. */
internal object VisibleContainerFacts {
    fun observe(world: NpcWorldView, accessProbe: NpcStockQuery): NpcBlockContainerObservation? {
        // Existing Core stock inspection checks real reach/visibility, locks and ungenerated loot
        // without opening it. Its count is irrelevant: only a successful access gate is used.
        val access = world.observeVisibleStock(accessProbe) as? NpcStockRead.Observed ?: return null
        if (access.position != accessProbe.position) return null
        val container = world.observeBlockContainer(accessProbe.position) ?: return null
        if (container.position != access.position || container.containerSize != access.slots || container.isTruncated ||
            container.slots.map { it.slot }.toSet() != (0 until container.containerSize).toSet()) return null
        return container
    }
}
