package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

internal data class MachinePort(val endpoint: NpcContainerEndpoint, val slot: Int, val itemId: String, val quantity: Int)
internal class MachineFeeds(ports: List<MachinePort>) {
    val ports: List<MachinePort> = java.util.List.copyOf(ports)
    override fun equals(other: Any?): Boolean = other is MachineFeeds && ports == other.ports
    override fun hashCode(): Int = ports.hashCode()
    override fun toString(): String = ports.toString()
}

/** A fixed feed/collect contract; processing remains entirely in the selected machine. */
internal data class MachineTaskDefinition(
    override val dimensionId: String,
    val feeds: MachineFeeds,
    val output: MachinePort,
    val anchor: NpcPosition,
    val travelRadius: Double = 32.0,
    val returnTo: NpcPosition? = null,
    val pollTicks: Int = 20,
    val noProgressTicks: Int = 1200,
    override val budget: TaskBudget = TaskBudget(),
    override val version: Int = 1,
) : TaskDefinition {
    override val operationId = ID
    val ports: List<MachinePort> = java.util.List.copyOf(feeds.ports + output)
    fun contains(position: NpcPosition): Boolean = position.x.isFinite() && position.y.isFinite() && position.z.isFinite() &&
        TaskNavigator.distanceSquared(position,anchor) <= travelRadius*travelRadius
    override fun validationProblem(): String? = when {
        version != 1 -> "unsupported machine task version"
        feeds.ports.size !in 1..4 -> "machine requires one to four supplied input ports"
        !travelRadius.isFinite() || travelRadius !in 4.0..64.0 -> "machine travel radius must be 4..64"
        NavigateTaskDefinition(dimensionId,anchor,budget=budget).validationProblem() != null -> "invalid machine anchor, dimension or budget"
        returnTo != null && !contains(returnTo) -> "machine return point is outside its fixed travel boundary"
        pollTicks !in 5..200 || noProgressTicks !in pollTicks..budget.ticks -> "invalid bounded machine polling/no-progress interval"
        ports.any { it.endpoint.dimensionId != dimensionId || it.endpoint.position != output.endpoint.position } -> "all ports must address one machine in the task dimension"
        ports.any { it.slot !in 0..63 || it.quantity !in 1..2304 || it.itemId.length > 256 || !ID_PATTERN.matches(it.itemId) } -> "invalid machine item, slot or quantity"
        feeds.ports.any { it.itemId == output.itemId } -> "output must differ from supplied input identities"
        feeds.ports.map { it.endpoint to it.slot }.distinct().size != feeds.ports.size -> "duplicate machine input port"
        feeds.ports.any { it.endpoint == output.endpoint && it.slot == output.slot } -> "input and output cannot use the same exposed slot"
        feeds.ports.sumOf { it.quantity } > 2304 -> "total machine feed exceeds carried inventory bounds"
        !contains(TransportTaskDefinition.center(output.endpoint.position)) -> "machine is outside the fixed travel boundary"
        else -> null
    }
    companion object {
        const val ID = "samcnpc:machine"
        private val ID_PATTERN = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    }
}
