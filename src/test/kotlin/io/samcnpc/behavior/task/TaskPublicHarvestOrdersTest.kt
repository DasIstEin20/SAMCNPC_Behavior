package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskPublicHarvestOrdersTest {
    companion object {
        private const val DIM = "minecraft:overworld"
        private val at = NpcPosition(0.5, 65.0, 0.5)
        private val base = NpcBlockPosition(8, 65, 0)
        private val field = OperationWorkArea(OperationWorkBox(base, NpcBlockPosition(9, 65, 1)))
        private val output = OperationContainers(listOf(NpcBlockPosition(4, 65, 0)))
        private val sources = OperationContainers(listOf(NpcBlockPosition(2, 65, 0)))
        fun examples(): List<OperationHarvestOrder> {
            val planting = OperationHarvestOrder.Planting(DIM,
                OperationPlantingWork(field, OperationSaplingSpecies.OAK, OperationPlantingMode.GAPS,
                    positions = listOf(base), sources = sources, keepSaplings = 1, sourceKeep = 2), 1, at)
            return listOf(
                OperationHarvestOrder.Mining(DIM, OperationMiningWork(field, OperationMiningMethod.EXPOSED,
                    OperationResourceIds(listOf("minecraft:iron_ore"))), OperationResourceIds(listOf("minecraft:raw_iron")),
                    output, 2, OperationMiningCounting.DELIVERED_ITEMS, at),
                OperationHarvestOrder.Farm(DIM, OperationFarmWork(field, OperationCrop.WHEAT,
                    OperationFarmMode.REPLANT, seedSources = sources, keepSeeds = 1, sourceKeepSeeds = 2), output, 1, at),
                planting,
                OperationHarvestOrder.Food(DIM, OperationFoodWork.Berries(field),
                    OperationResourceIds(listOf("minecraft:sweet_berries")), output, 4, 2, at),
                OperationHarvestOrder.Lumberjack(DIM, OperationWorkArea(OperationWorkBox(base, NpcBlockPosition(9, 70, 1))),
                    OperationWoodSelection(listOf("samcnpc:oak_and_birch")), output.positions.single(), 4,
                    supplySources = sources, replant = planting),
            )
        }
    }
    private fun valid(order: OperationOrder): TaskDefinition {
        val result = OperationSupervisionApi.validateOrder(order)
        assertEquals(NpcActionStatus.SUCCEEDED, result.status, result.detail)
        val definition = TaskPublicOrders.definition(order)
        val bytes = TaskCodec.writeDefinition(definition)
        assertEquals(bytes, TaskCodec.writeDefinition(TaskCodec.readDefinition(bytes)))
        return definition
    }
    private fun invalid(order: OperationOrder) {
        val result = OperationSupervisionApi.validateOrder(order)
        assertEquals(NpcActionStatus.REJECTED, result.status)
        assertTrue(result.detail.isNotBlank())
    }

    @Test fun boundedResourceAreaAndPlantingListsCannotChangeAfterHandoff() {
        val names = mutableListOf("minecraft:iron_ore")
        val resource = OperationResourceIds(names); names.clear(); assertEquals(1, resource.values.size)
        assertFailsWith<UnsupportedOperationException> { (resource.values as MutableList).clear() }
        for (bad in listOf(emptyList(), List(65) { "minecraft:iron_ore" }, listOf("bad id"), listOf("minecraft:iron_ore", "minecraft:iron_ore")))
            assertFailsWith<IllegalArgumentException> { OperationResourceIds(bad) }
        val exclusions = mutableListOf(OperationWorkBox(base, base))
        val area = OperationWorkArea(field.bounds, exclusions); exclusions.clear(); assertEquals(1, area.exclusions.size)
        assertFailsWith<UnsupportedOperationException> { (area.exclusions as MutableList).clear() }
        assertFailsWith<IllegalArgumentException> { OperationWorkArea(field.bounds, List(17) { field.bounds }) }
        val positions = mutableListOf(base)
        val planting = OperationPlantingWork(field, OperationSaplingSpecies.OAK, OperationPlantingMode.PATCH, positions = positions)
        positions.clear(); assertEquals(listOf(base), planting.positions)
        assertFailsWith<UnsupportedOperationException> { (planting.positions as MutableList).clear() }
        assertFailsWith<IllegalArgumentException> { OperationPlantingWork(field, OperationSaplingSpecies.OAK, OperationPlantingMode.PATCH, positions = List(129) { base }) }
    }

    @Test fun miningPermissionsCountingAndDirectionalGeometryRemainIndependent() {
        val order = examples()[0] as OperationHarvestOrder.Mining
        for (method in listOf(OperationMiningMethod.EXPOSED, OperationMiningMethod.VEIN, OperationMiningMethod.EXCAVATION)) {
            val mapped = valid(order.copy(work = order.work.copy(method = method))) as MiningTaskDefinition
            assertEquals(method.name, mapped.work.method.name)
        }
        invalid(order.copy(work = order.work.copy(access = OperationResourceIds(listOf("minecraft:stone")))))
        invalid(order.copy(counting = OperationMiningCounting.CLEARED_VOLUME))
        val extent = mapOf(OperationTunnelDirection.EAST to NpcBlockPosition(11, 65, 0),
            OperationTunnelDirection.WEST to NpcBlockPosition(5, 65, 0),
            OperationTunnelDirection.SOUTH to NpcBlockPosition(8, 65, 3),
            OperationTunnelDirection.NORTH to NpcBlockPosition(8, 65, -3))
        for ((direction, end) in extent) {
            val box = OperationWorkBox(NpcBlockPosition(minOf(base.x, end.x), 65, minOf(base.z, end.z)),
                NpcBlockPosition(maxOf(base.x, end.x), 66, maxOf(base.z, end.z)))
            val geometry = OperationTunnelGeometry(base, direction, 1, 2, 4)
            val request = order.copy(work = order.work.copy(area = OperationWorkArea(box), method = OperationMiningMethod.TUNNEL,
                tunnel = geometry, access = OperationResourceIds(listOf("minecraft:stone"))), quantity = 1,
                counting = OperationMiningCounting.CLEARED_VOLUME)
            val mapped = valid(request) as MiningTaskDefinition
            assertEquals(8, mapped.clearanceCells)
            assertEquals(base.copy(y = 66), mapped.work.cell(0)); assertEquals(end, mapped.work.cell(7))
            invalid(request.copy(quantity = 2))
            invalid(request.copy(work = request.work.copy(tunnel = geometry.copy(height = 1))))
        }
        invalid(order.copy(destinations = OperationContainers(listOf(base))))
        invalid(order.copy(work = order.work.copy(area = OperationWorkArea(OperationWorkBox(base, base.copy(x = base.x + 50))))))
    }

    @Test fun cropModesKeepSeedSupplyGrowthAndFieldBoundaries() {
        val order = examples()[1] as OperationHarvestOrder.Farm
        for (crop in OperationCrop.entries) for (mode in OperationFarmMode.entries) {
            val work = OperationFarmWork(field, crop, mode, seedSources = if (mode == OperationFarmMode.HARVEST) null else sources)
            val mapped = valid(order.copy(work = work)) as FarmTaskDefinition
            assertEquals(crop.name, mapped.work.crop.name); assertEquals(mode.name, mapped.work.mode.name)
        }
        invalid(order.copy(work = order.work.copy(mode = OperationFarmMode.HARVEST)))
        invalid(order.copy(work = order.work.copy(growthWaitTicks = 20, growthCheckTicks = 100)))
        invalid(order.copy(work = order.work.copy(seedSources = OperationContainers(listOf(base)))))
        invalid(order.copy(work = order.work.copy(keepSeeds = 513)))
        invalid(order.copy(quantity = 2305))
    }

    @Test fun speciesLayoutsHonorEveryAuthorizedFootprintCellAndSupplyReserve() {
        val order = examples()[2] as OperationHarvestOrder.Planting
        for (species in OperationSaplingSpecies.entries) for (mode in OperationPlantingMode.entries) {
            val request = order.copy(work = OperationPlantingWork(field, species, mode, positions = listOf(base),
                sources = sources, keepSaplings = 2, sourceKeep = 3))
            val mapped = valid(request) as PlantingTaskDefinition
            assertEquals(species.name, mapped.work.species.name); assertEquals(mode.name, mapped.work.mode.name)
            assertEquals(if (species == OperationSaplingSpecies.DARK_OAK) 4 else 1, mapped.work.species.footprint(base).size)
            assertEquals(2, mapped.work.keepSaplings); assertEquals(3, mapped.work.sourceKeep)
        }
        val excluded = OperationWorkArea(field.bounds, listOf(OperationWorkBox(base.copy(x = 9, z = 1), base.copy(x = 9, z = 1))))
        invalid(order.copy(work = OperationPlantingWork(excluded, OperationSaplingSpecies.DARK_OAK,
            OperationPlantingMode.GAPS, positions = listOf(base))))
        invalid(order.copy(work = OperationPlantingWork(field, OperationSaplingSpecies.DARK_OAK,
            OperationPlantingMode.GAPS, spacing = 3)))
        invalid(order.copy(quantity = 129))
    }

    @Test fun foodVariantsCannotAuthorizePlayersOrInventSourceAndRetainedStock() {
        val order = examples()[3] as OperationHarvestOrder.Food
        val hunt = OperationFoodWork.Hunt(field, NpcEntityTypeFilter.of(setOf("minecraft:cow")), 3)
        for (work in listOf(OperationFoodWork.Drops(field), OperationFoodWork.Berries(field),
            OperationFoodWork.Stored(sources, 2), hunt)) valid(order.copy(work = work))
        invalid(order.copy(work = hunt.copy(targets = NpcEntityTypeFilter.ANY)))
        invalid(order.copy(work = hunt.copy(targets = NpcEntityTypeFilter.of(setOf("minecraft:player")))))
        invalid(order.copy(work = hunt.copy(limit = 17)))
        invalid(order.copy(work = OperationFoodWork.Stored(output)))
        invalid(order.copy(outputs = OperationResourceIds(listOf("minecraft:raw_iron"))))
        invalid(order.copy(keepFood = 65))
    }

    @Test fun woodV2KeepsSeparateSourcesAndOnlyCompatibleFiniteReplanting() {
        val order = examples()[4] as OperationHarvestOrder.Lumberjack
        val mapped = valid(order) as LumberjackTaskDefinition
        assertEquals(2, mapped.version); assertEquals(sources.positions, mapped.supplySources?.positions)
        assertEquals(1, mapped.replant?.quantity)
        invalid(order.copy(wood = OperationWoodSelection(listOf("samcnpc:unknown"))))
        invalid(order.copy(replant = checkNotNull(order.replant).copy(dimensionId = "minecraft:the_nether")))
        invalid(order.copy(replant = checkNotNull(order.replant).copy(work = OperationPlantingWork(field,
            OperationSaplingSpecies.OAK, OperationPlantingMode.PATCH, positions = listOf(base)))))
        invalid(order.copy(quantity = 0))
    }
}
