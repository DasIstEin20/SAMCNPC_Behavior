package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition

/** Concrete common cargo contract; each profession still owns selection and completion policy. */
internal sealed interface ProducedTaskDefinition : LocalWorkDefinition {
    val outputs: WorkResourceIds
    val destinations: ContainerChoices
}

internal open class ProducedCargoState(val resources: ProducedResources) {
    var selectedContainer: NpcBlockPosition? = null
    val deliveries=linkedMapOf<NpcBlockPosition,Map<String,Int>>()
    val checkpoints=linkedMapOf<NpcBlockPosition,ContainerCheckpoint>()
    fun delivered(definition: ProducedTaskDefinition) = definition.outputs.values.sumOf(resources::delivered)
    fun cargo(definition: ProducedTaskDefinition) = definition.outputs.values.sumOf(resources::available)
}
