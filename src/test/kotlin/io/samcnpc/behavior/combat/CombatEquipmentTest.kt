package io.samcnpc.behavior.combat

import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class CombatEquipmentTest {
    private val sword = NpcItemKnowledge("test:sword", setOf(NpcItemRole.MELEE_WEAPON), combat = NpcCombatItemFacts(meleeDamageAddition = 6.0, attackSpeedAddition = -2.4))
    private val bow = NpcItemKnowledge("test:bow", setOf(NpcItemRole.RANGED_WEAPON), combat = NpcCombatItemFacts(rangedWeapon = NpcRangedWeaponKind.BOW, requiresArrow = true, rangedSupported = true))
    private val arrow = NpcItemKnowledge("test:arrow", setOf(NpcItemRole.AMMUNITION))
    private fun entry(slot: Int, item: NpcItemKnowledge, count: Int = 1) = NpcInventoryEntry(slot, NpcItemStackSnapshot(item.itemId, count, 64, 0, 0), item)
    private fun snapshot(held: NpcItemKnowledge = NpcItemKnowledge.EMPTY) = decisionContext().snapshot.copy(equipment = NpcEquipmentKnowledge.EMPTY.copy(mainHand = held))

    @Test fun preferredRangedFallsBackToCarriedMeleeButHardAllowanceDoesNot() {
        val inventory = listOf(entry(3, sword), entry(5, bow))
        val preference = CombatTactics(preference = CombatWeaponPreference.RANGED)
        assertEquals(3, CombatEquipment.choose(snapshot(), inventory, preference, 10.0)?.slot)
        assertNull(CombatEquipment.choose(snapshot(), inventory, preference.copy(allowed = CombatWeaponAllowance.RANGED), 10.0))
        assertEquals(5, CombatEquipment.choose(snapshot(), inventory + entry(9, arrow, 8), preference, 10.0)?.slot)
    }
    @Test fun missingMeleeCanUseLoadedRangedAndChargedCrossbowNeedsNoNewArrow() {
        val inventory = listOf(entry(4, bow), entry(7, arrow))
        assertEquals(4, CombatEquipment.choose(snapshot(), inventory, CombatTactics(preference = CombatWeaponPreference.MELEE), 3.0)?.slot)
        val charged = bow.copy(combat = bow.combat.copy(rangedWeapon = NpcRangedWeaponKind.CROSSBOW, requiresArrow = false))
        assertTrue(CombatEquipment.canShoot(charged, emptyList(), NpcEquipmentKnowledge.EMPTY))
        assertFalse(CombatEquipment.canShoot(bow, emptyList(), NpcEquipmentKnowledge.EMPTY))
    }
    @Test fun equalWeaponsRetainTheSelectedSlotAndDistanceHasHysteresis() {
        val snap = snapshot(sword).copy(selectedHotbarSlot = 3)
        assertNull(CombatEquipment.choose(snap, listOf(entry(1, sword), entry(3, sword)), CombatTactics(), 2.0))
        val policy = CombatTactics()
        assertFalse(CombatEquipment.preferRanged(policy, 4.2, currentlyRanged = false))
        assertTrue(CombatEquipment.preferRanged(policy, 3.8, currentlyRanged = true))
        assertTrue(CombatEquipment.preferRanged(policy, 5.1, currentlyRanged = false))
        assertFalse(CombatEquipment.preferRanged(policy, 2.9, currentlyRanged = true))
    }
    @Test fun activeUseAndRangedChargeForbidEveryAutomaticSwap() {
        val inventory = listOf(entry(3, sword))
        val using = snapshot().copy(itemUse = NpcItemUseState(NpcHand.MAIN, "test:medicine", 25, 7, UUID.randomUUID()))
        assertNull(CombatEquipment.choose(using, inventory, CombatTactics(), 2.0))
        val charging = snapshot().copy(rangedAttack = NpcRangedAttackState(UUID.randomUUID(), NpcHand.MAIN, NpcRangedWeaponKind.BOW, NpcRangedAttackPhase.CHARGING, 10, 20, UUID.randomUUID()))
        assertNull(CombatEquipment.choose(charging, inventory, CombatTactics(), 2.0))
    }
    @Test fun armorAndHealingUseObservedFactsAndOneStableChoice() {
        val helmet = NpcItemKnowledge("test:helmet", setOf(NpcItemRole.ARMOR), armorDestination = NpcEquipmentDestination.HEAD, combat = NpcCombatItemFacts(armorDefense = 3))
        val medicine = NpcItemKnowledge("test:medicine", setOf(NpcItemRole.OTHER), combat = NpcCombatItemFacts(healingConsumable = true, healingStrength = 2, useTicks = 32))
        val food = medicine.copy(itemId = "test:ordinary_food", combat = NpcCombatItemFacts(useTicks = 32))
        val inventory = listOf(entry(5, helmet), entry(2, medicine), entry(0, food, 64))
        assertEquals(CombatEquipmentChoice(5, NpcEquipmentDestination.HEAD), CombatEquipment.choose(snapshot(), inventory, CombatTactics(preference = CombatWeaponPreference.CURRENT), 8.0))
        assertEquals(2, CombatEquipment.healingItem(inventory)?.slot)
        assertNull(CombatEquipment.healingItem(listOf(entry(0, food, 64))))
        assertNull(CombatEquipment.healingItem(listOf(entry(2, medicine, 0))))
    }
    @Test fun incompatibleConstraintsAndUnsafeThresholdsAreRejected() {
        for (policy in listOf(CombatTactics(retreatAt = 0.7, returnAt = 0.4), CombatTactics(retreatAt = Double.NaN),
            CombatTactics(rangedMinDistance = 12.0, rangedMaxDistance = 8.0), CombatTactics(preference = CombatWeaponPreference.MELEE, allowed = CombatWeaponAllowance.RANGED))) assertNotNull(policy.validationProblem())
    }
}
