package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskPublicTacticsInventoryTest {
    private val dim = "minecraft:overworld"
    private val at = NpcPosition(0.5, 65.0, 0.5)
    private val target = UUID(2, 3)
    private val filter = NpcEntityTypeFilter.of(setOf("minecraft:husk"))
    private val containers = OperationContainers(listOf(NpcBlockPosition(3, 65, 0)))
    private fun valid(order: OperationOrder) = assertEquals(NpcActionStatus.SUCCEEDED,
        OperationSupervisionApi.validateOrder(order).status)
    private fun invalid(order: OperationOrder) {
        val result = OperationSupervisionApi.validateOrder(order)
        assertEquals(NpcActionStatus.REJECTED, result.status)
        assertTrue(result.detail.isNotBlank())
    }

    @Test fun tacticalPreferencesCannotBypassHardWeaponHealthAndDistanceBounds() {
        val attack = OperationCombatOrder.Attack(dim, target, at)
        valid(attack)
        assertEquals(2, TaskPublicOrders.definition(attack).version)
        for (preference in OperationWeaponPreference.entries) for (allowance in OperationWeaponAllowance.entries) {
            val tactics = OperationCombatTactics(preference, allowance)
            val request = attack.copy(tactics = tactics)
            val contradicts = preference == OperationWeaponPreference.MELEE && allowance == OperationWeaponAllowance.RANGED ||
                preference == OperationWeaponPreference.RANGED && allowance == OperationWeaponAllowance.MELEE
            if (contradicts) invalid(request) else valid(request)
        }
        for (tactics in listOf(OperationCombatTactics(retreatAt = Double.NaN),
            OperationCombatTactics(retreatAt = 0.6, returnAt = 0.5),
            OperationCombatTactics(rangedMinDistance = 8.0, rangedMaxDistance = 6.0))) invalid(attack.copy(tactics = tactics))
        invalid(attack.copy(leash = 33.0)); invalid(attack.copy(budget = OperationBudget(ticks = 2401)))
        val defense = OperationCombatOrder.Defend(dim, at, 20.0, filter = filter)
        valid(defense); invalid(defense.copy(filter = NpcEntityTypeFilter.ANY))
        valid(defense.copy(filter = NpcEntityTypeFilter.ANY, subjectUuid = target))
        invalid(defense.copy(dutyTicks = 1590)); invalid(defense.copy(returnTo = at.copy(x = 25.0)))
        val area = OperationCombatOrder.AreaAttack(dim, at, 20.0, filter, 1)
        valid(area); invalid(area.copy(quota = 0)); invalid(area.copy(filter = NpcEntityTypeFilter.ANY))
    }

    @Test fun patrolKeepsBoundedImmutableWaypointsAndExplicitReactionSubjects() {
        val source = mutableListOf(at, at.copy(x = 8.0))
        val patrol = OperationCombatOrder.Patrol(dim, at, 16.0, source)
        source.clear(); valid(patrol); assertEquals(2, patrol.route.size)
        assertFailsWith<UnsupportedOperationException> { (patrol.route as MutableList).clear() }
        assertFailsWith<IllegalArgumentException> { OperationCombatOrder.Patrol(dim, at, 16.0, List(17) { at }) }
        invalid(OperationCombatOrder.Patrol(dim, at, 16.0, listOf(at.copy(x = 20.0))))
        for (reaction in OperationPatrolReaction.entries) {
            val order = OperationCombatOrder.Patrol(dim, at, 16.0, listOf(at), reaction = reaction,
                subjectUuid = if (reaction == OperationPatrolReaction.PROTECT_SUMMONER) target else null,
                supportTargetUuid = if (reaction == OperationPatrolReaction.SUPPORT) target else null, filter = filter)
            valid(order)
            val definition = TaskPublicOrders.definition(order)
            val original = TaskCodec.writeDefinition(definition)
            assertEquals(original, TaskCodec.writeDefinition(TaskCodec.readDefinition(original)))
        }
        invalid(OperationCombatOrder.Patrol(dim, at, 16.0, listOf(at), reaction = OperationPatrolReaction.SUPPORT))
        invalid(OperationCombatOrder.Patrol(dim, at, 16.0, listOf(at), subjectUuid = target))
    }

    @Test fun inventoryOrdersPreserveFiniteStockReservesPickupAndPhysicalReturnBounds() {
        val variants = listOf(
            OperationInventoryWork.Supply(listOf(OperationStockNeed("minecraft:oak_log", 4, 8, 3)), containers),
            OperationInventoryWork.Unload(listOf(OperationItemReserve("minecraft:oak_log", 4)), containers),
            OperationInventoryWork.Pickup(listOf("minecraft:oak_log"), 8.0, 256),
        )
        for (work in variants) {
            val order = OperationInventoryOrder(dim, work, at)
            valid(order)
            val original = TaskCodec.writeDefinition(TaskPublicOrders.definition(order))
            assertEquals(original, TaskCodec.writeDefinition(TaskCodec.readDefinition(original)))
            invalid(order.copy(workTicks = 1190)); invalid(order.copy(maxSteps = 129))
            invalid(order.copy(returnTo = at.copy(x = 30.0)))
        }
        val invalidWorks = listOf(
            OperationInventoryWork.Supply(listOf(OperationStockNeed("minecraft:oak_log", 9, 8)), containers),
            OperationInventoryWork.Supply(listOf(OperationStockNeed("minecraft:oak_log", 1, 1500), OperationStockNeed("minecraft:birch_log", 1, 1500)), containers),
            OperationInventoryWork.Unload(listOf(OperationItemReserve("minecraft:oak_log", -1)), containers),
            OperationInventoryWork.Pickup(listOf("minecraft:oak_log", "minecraft:oak_log")),
            OperationInventoryWork.Pickup(listOf("bad id")),
            OperationInventoryWork.Pickup(listOf("minecraft:oak_log"), Double.NaN),
            OperationInventoryWork.Pickup(listOf("minecraft:oak_log"), maxItems = 257),
        )
        for (work in invalidWorks) invalid(OperationInventoryOrder(dim, work, at))
    }

    @Test fun inventoryInputsCannotChangeAfterHandoffOrAllocateUnboundedLists() {
        val needs = mutableListOf(OperationStockNeed("minecraft:oak_log", 1, 4))
        val reserves = mutableListOf(OperationItemReserve("minecraft:oak_log", 2))
        val ids = mutableListOf("minecraft:oak_log")
        val supply = OperationInventoryWork.Supply(needs, containers)
        val unload = OperationInventoryWork.Unload(reserves, containers)
        val pickup = OperationInventoryWork.Pickup(ids)
        needs.clear(); reserves.clear(); ids.clear()
        assertEquals(1, supply.needs.size); assertEquals(1, unload.reserves.size); assertEquals(1, pickup.itemIds.size)
        assertFailsWith<UnsupportedOperationException> { (supply.needs as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (unload.reserves as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (pickup.itemIds as MutableList).clear() }
        assertFailsWith<IllegalArgumentException> { OperationInventoryWork.Supply(emptyList(), containers) }
        assertFailsWith<IllegalArgumentException> { OperationInventoryWork.Unload(List(17) { OperationItemReserve("minecraft:oak_log", 0) }, containers) }
        assertFailsWith<IllegalArgumentException> { OperationInventoryWork.Pickup(List(17) { "minecraft:oak_log" }) }
    }
}
