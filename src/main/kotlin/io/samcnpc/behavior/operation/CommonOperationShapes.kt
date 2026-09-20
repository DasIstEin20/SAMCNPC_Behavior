package io.samcnpc.behavior.operation

import io.samcnpc.behavior.api.*

internal object CommonOperationShapes {
    fun values(): Map<String, OperationInput> = linkedMapOf(
        "position" to record(
            required("x", number(-29999984.0, 29999984.0)),
            required("y", number(-2048.0, 2048.0)),
            required("z", number(-29999984.0, 29999984.0))),
        "block" to record(
            required("x", integer(-29999984, 29999984, "blocks")),
            required("y", integer(-2048, 2048, "blocks")),
            required("z", integer(-29999984, 29999984, "blocks"))),
        "box" to record(listOf(required("min", ref("block")), required("max", ref("block"))),
            relation("BOX_EXTENT", "Inclusive ordered corners: width/depth 1..50, height 1..64 blocks.", "min", "max")),
        "area" to record(listOf(required("bounds", ref("box")), optional("exclusions", list(ref("box"), 0, 16, true), "[]")),
            relation("EXCLUSIONS_INSIDE", "Every excluded box must lie wholly inside bounds.", "bounds", "exclusions")),
        "containers" to record(
            required("positions", list(ref("block"), 1, 8, true)),
            optional("preference", choice(*OperationContainerPreference.entries.map { it.name }.toTypedArray()), "\"ORDERED\"")),
        "resources" to list(id, 1, 64, true),
        "filter" to record(listOf(
            optional("typeIds", list(id, 0, 32, true), "[]"),
            optional("tagIds", list(id, 0, 32, true), "[]")),
            relation("FILTER_SIZE", "At most 32 type IDs and tag IDs combined; both empty means ANY.", "typeIds", "tagIds")),
        "endpoint" to record(required("dimensionId", id), required("position", ref("block")),
            nullable("side", choice("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"))),
        "port" to record(required("endpoint", ref("endpoint")), required("slot", integer(0, 63, "slot")),
            required("itemId", id), required("quantity", integer(1, 2304, "items"))),
        "budget" to budget(),
        "attackBudget" to budget(600, 2400),
        "defendBudget" to budget(1600),
        "areaBudget" to budget(2400),
        "inventoryBudget" to budget(1200),
        "farmBudget" to budget(12000),
        "tactics" to tactics(false),
        "heldTactics" to tactics(true),
        "need" to record(listOf(required("itemId", id), required("minimum", integer(1, 2304, "items")),
            required("target", integer(1, 2304, "items")), optional("sourceReserve", integer(0, 2304, "items"), "0")),
            relation("SUPPLY_MINIMUM", "target >= minimum.", "minimum", "target")),
        "reserve" to record(required("itemId", id), required("keep", integer(0, 2304, "items"))),
        "supply" to record(listOf(required("kind", choice("SUPPLY")), required("needs", list(ref("need"), 1, 16)),
            required("sources", ref("containers"))),
            relation("SUPPLY_TOTAL", "Distinct item IDs; sum of target counts <= 2304.", "needs")),
        "unload" to record(listOf(required("kind", choice("UNLOAD")), required("reserves", list(ref("reserve"), 1, 16)),
            required("destinations", ref("containers"))),
            relation("UNLOAD_IDS", "Reserve item IDs must be distinct.", "reserves")),
        "pickup" to record(required("kind", choice("PICKUP")), required("itemIds", list(id, 1, 16, true)),
            optional("radius", number(1.0, 8.0), "4.0"), optional("maxItems", integer(1, 256, "items"), "32")),
        "inventoryWork" to OperationInput.Alternatives(listOf("supply", "unload", "pickup")),
    )

    private fun tactics(held: Boolean): OperationInput.Record {
        val defaults = if (held) OperationCombatTactics.HELD_MELEE else OperationCombatTactics()
        return record(listOf(
            optional("preference", choice(*OperationWeaponPreference.entries.map { it.name }.toTypedArray()), "\""+defaults.preference.name+"\""),
            optional("allowed", choice(*OperationWeaponAllowance.entries.map { it.name }.toTypedArray()), "\""+defaults.allowed.name+"\""),
            optional("equipArmor", OperationInput.Flag, defaults.equipArmor.toString()),
            optional("useShield", OperationInput.Flag, defaults.useShield.toString()),
            optional("heal", OperationInput.Flag, defaults.heal.toString()),
            optional("retreatAt", number(0.0, 0.9, "health_fraction"), defaults.retreatAt.toString()),
            optional("returnAt", number(0.1, 1.0, "health_fraction"), defaults.returnAt.toString()),
            optional("rangedMinDistance", number(2.5, 12.0), defaults.rangedMinDistance.toString()),
            optional("rangedMaxDistance", number(4.0, 24.0), defaults.rangedMaxDistance.toString())),
            relation("TACTICAL_THRESHOLDS", "returnAt > retreatAt; rangedMaxDistance > rangedMinDistance.", "retreatAt", "returnAt", "rangedMinDistance", "rangedMaxDistance"),
            relation("WEAPON_CONSTRAINT", "MELEE preference cannot select RANGED-only allowance; RANGED preference cannot select MELEE-only.", "preference", "allowed"))
    }
}
