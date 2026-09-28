package io.samcnpc.behavior.task

import io.samcnpc.behavior.inventory.LocalTaskFacts
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** Capture only data already owned by the durable task; condition handlers never access its store. */
internal object TaskLocalFacts {
    fun containerQueries(server: MinecraftServer, npc: UUID, requests: List<io.samcnpc.behavior.inventory.ContainerFactRequest>): Map<io.samcnpc.behavior.inventory.ContainerFactRequest, io.samcnpc.core.api.NpcStockQuery> {
        val record = TaskStore.forServer(server).get(npc) ?: return emptyMap()
        if (record.status.terminal) return emptyMap()
        val work = (record.active.definition as? InventoryTaskDefinition)?.work
        val parent = record.primary.definition
        val sources = if (work is EnsureItems || work is SupplyStock || work is CollectContainer) work.containers else
            record.logistics.policy.preparation?.containers ?: record.logistics.policy.supply?.containers ?: when (parent) {
                is TransportTaskDefinition -> parent.sources
                is LumberjackTaskDefinition -> parent.supplySources
                is FoodTaskDefinition -> (parent.work as? FoodWorkOrder.Stored)?.sources
                else -> null
            }
        val destinations = if (work is UnloadExcess) work.containers else record.logistics.policy.unload?.containers ?: when (parent) {
            is TransportTaskDefinition -> parent.destinations
            is MiningTaskDefinition -> parent.destinations
            is FarmTaskDefinition -> parent.destinations
            is FoodTaskDefinition -> parent.destinations
            else -> null
        }
        val result = linkedMapOf<io.samcnpc.behavior.inventory.ContainerFactRequest, io.samcnpc.core.api.NpcStockQuery>()
        for (request in requests) {
            val choices = if (request.endpoint == io.samcnpc.behavior.inventory.ContainerFactEndpoint.SOURCE) sources else destinations
            val position = choices?.positions?.getOrNull(request.index) ?: continue
            val query = choices.accessProbes[position] ?: continue
            result[request] = query
        }
        return result
    }
    fun capture(server: MinecraftServer, npc: UUID): LocalTaskFacts? {
        val record = TaskStore.forServer(server).get(npc) ?: return null
        val active = record.active
        val failure = if (record.status == TaskStatus.FAILED) record.reason.name else if (active.failures > 0) active.reason.name else null
        return LocalTaskFacts(record.status.name, (active.definition.budget.attempts - active.failures).coerceAtLeast(0), failure)
    }
}
