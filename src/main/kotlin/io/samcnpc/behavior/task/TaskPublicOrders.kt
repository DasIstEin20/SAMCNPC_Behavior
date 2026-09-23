package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Pure definition conversion plus one authorized assignment path; no alternate executor. */
internal object TaskPublicOrders {
    fun validate(order: OperationOrder): NpcActionResult {
        val problem = definition(order).validationProblem()
        return if (problem == null) NpcActionResult.succeeded("operation definition is valid; world state still requires assignment validation")
        else NpcActionResult.rejected(problem)
    }

    fun assign(server: MinecraftServer, actor: ServerPlayer, npcUuid: UUID, request: OperationAssignmentRequest): OperationReply =
        TaskSupervision.withNpc(server, actor, npcUuid) { npc ->
            val observed = TaskSupervision.report(server, npc)
            if (observed.result.status != NpcActionStatus.SUCCEEDED) return@withNpc observed
            val prior = TaskStore.forServer(server).get(npcUuid)
            val rejection = TaskAssignmentGuard.rejection(prior, request, checkNotNull(observed.observation).observedTick)
            if (rejection != null) return@withNpc OperationReply(rejection, observed.observation)
            val result = TaskService.assign(server, npc, definition(request.order))
            OperationReply(result, TaskSupervision.report(server, npc).observation)
        }

    internal fun definition(order: OperationOrder): TaskDefinition {
        val budget = TaskBudget(order.budget.ticks, order.budget.attempts, order.budget.backoffTicks)
        val version = order.type.definitionVersion
        return when (order) {
            is OperationPrepareFieldOrder -> PrepareFieldTaskDefinition(order.dimensionId,WorkArea(WorkBox(order.area.bounds.min,order.area.bounds.max),order.area.exclusions.map { WorkBox(it.min,it.max) }),order.anchor,order.travelRadius,order.returnTo,budget,version)
            is OperationHarvestOrder -> TaskPublicHarvestOrders.definition(order, budget)
            is OperationCombatOrder -> TaskPublicCombatOrders.definition(order, budget)
            is OperationInventoryOrder -> TaskPublicInventoryOrders.definition(order, budget)
            is OperationOrder.Navigate -> NavigateTaskDefinition(order.dimensionId, order.destination, order.speed, order.arrivalDistance, budget, version)
            is OperationOrder.Deliver -> DeliveryTaskDefinition(order.dimensionId, order.destination, order.itemId, order.quantity, order.keepAtLeast, budget, version, order.anchor)
            is OperationOrder.Transport -> TransportTaskDefinition(order.dimensionId, containers(order.sources), containers(order.destinations),
                order.itemId, order.quantity, order.anchor, order.travelRadius, order.keepAtLeast, order.sourceKeepAtLeast, order.returnTo, budget, version)
            is OperationOrder.Machine -> MachineTaskDefinition(order.dimensionId, MachineFeeds(order.feeds.ports.map(::port)), port(order.output),
                order.anchor, order.travelRadius, order.returnTo, order.pollTicks, order.noProgressTicks, budget, version)
            is OperationOrder.Fish -> FishingTaskDefinition(order.dimensionId, order.water, order.standing, order.catches, order.anchor,
                order.travelRadius, order.returnTo, order.pickupWaitTicks, budget, version)
            is OperationOrder.Explore -> ExplorerTaskDefinition(order.dimensionId, order.anchor, order.radius, order.cellStep,
                order.maxCells, order.verticalRange, order.chunkBudget, order.heading, budget, version)
        }
    }

    private fun port(value: OperationMachinePort) = MachinePort(value.endpoint, value.slot, value.itemId, value.quantity)
    internal fun containers(value: OperationContainers) = ContainerChoices(value.positions, when (value.preference) {
        OperationContainerPreference.ORDERED -> ContainerPreference.ORDERED
        OperationContainerPreference.NEAREST -> ContainerPreference.NEAREST
    })
}
