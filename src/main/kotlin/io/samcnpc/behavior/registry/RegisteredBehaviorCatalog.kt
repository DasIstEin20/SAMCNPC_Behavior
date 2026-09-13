package io.samcnpc.behavior.registry

import io.samcnpc.behavior.api.BehaviorComponentCatalog
import io.samcnpc.behavior.api.BehaviorComponentDescriptor
import io.samcnpc.behavior.api.BehaviorComponentKind
import io.samcnpc.behavior.api.BehaviorNumberDefault
import io.samcnpc.behavior.api.BehaviorNumericKind
import io.samcnpc.behavior.api.BehaviorParameter
import java.util.Locale

/** Metadata has an explicit entry for every registered ID; new definitions cannot silently guess no args. */
internal object RegisteredBehaviorCatalog {
    private val comparisons = BehaviorParameter.Choice("operator", true,
        "Compare the observed value with the supplied value.", listOf("gt", "gte", "lt", "lte", "eq"))
    private val movement = listOf(
        number("speed", 0.1, 1.5, "Values above 1 request ordinary sprint; no hidden physics boost."),
        number("stopDistance", 0.0, 16.0, "Horizontal stopping distance in blocks."),
    )
    private val conditionParameters: Map<String, List<BehaviorParameter>> = mapOf(
        "samcnpc:distance_to_summoner" to listOf(comparisons, number("blocks", 0.0, 256.0, "Horizontal distance in blocks.")),
        "samcnpc:health_fraction" to listOf(comparisons, number("value", 0.0, 1.0, "Current health divided by maximum health.")),
        "samcnpc:was_hurt_recently" to listOf(number("withinTicks", 1.0, 120000.0, "Elapsed game ticks since damage.", integer = true)),
    )
    private val actionParameters: Map<String, List<BehaviorParameter>> = mapOf(
        "samcnpc:move_to_target" to movement,
        "samcnpc:move_to_summoner" to movement + BehaviorParameter.Numeric(
            "startDistance", false, "Horizontal distance needed to start following after stopping.",
            BehaviorNumericKind.NUMBER, 0.25, 32.0, BehaviorNumberDefault.FromParameter("stopDistance", 2.0), "stopDistance"),
        "samcnpc:set_attack_target_from_recent_attacker" to listOf(
            number("leash", 1.0, 32.0, "Fixed retaliation pursuit radius in blocks.", required = false, default = 24.0),
            number("durationTicks", 20.0, 2400.0, "Original retaliation deadline in game ticks.", integer = true, required = false, default = 600.0),
            BehaviorParameter.Flag("allowPlayers", false, "Allow eligible players; summoner/friendly checks still apply.", false),
        ),
    )
    private val noArgumentConditions = setOf(
        "samcnpc:always", "samcnpc:task_ready", "samcnpc:task_combat_ready",
        "samcnpc:task_reaction_ready", "samcnpc:task_inventory_ready", "samcnpc:task_inventory_requested",
        "samcnpc:has_summoner", "samcnpc:summoner_online", "samcnpc:has_unhandled_damage",
        "samcnpc:has_attack_target", "samcnpc:target_alive",
    )
    private val noArgumentActions = setOf(
        "samcnpc:attack_target", "samcnpc:begin_task_inventory", "samcnpc:begin_task_reaction",
        "samcnpc:clear_attack_target", "samcnpc:look_at_summoner", "samcnpc:look_at_target",
        "samcnpc:run_combat_task", "samcnpc:run_delivery_task", "samcnpc:run_explorer_task",
        "samcnpc:run_farm_task", "samcnpc:run_fishing_task", "samcnpc:run_food_task",
        "samcnpc:run_inventory_task", "samcnpc:run_lumberjack_demo", "samcnpc:run_lumberjack_task",
        "samcnpc:run_machine_task", "samcnpc:run_mining_task", "samcnpc:run_navigation_task",
        "samcnpc:run_planting_task", "samcnpc:stop_movement",
    )

    val snapshot: BehaviorComponentCatalog = create()

    private fun create(): BehaviorComponentCatalog {
        val conditions = BehaviorDefinitions.conditions
        val actions = BehaviorDefinitions.actions
        check(conditions.keys == noArgumentConditions + conditionParameters.keys) {
            "Condition catalog and registered IDs differ; update the explicit parameter contract."
        }
        check(actions.keys == noArgumentActions + actionParameters.keys) {
            "Action catalog and registered IDs differ; update the explicit parameter contract."
        }
        val conditionRows = conditions.keys.sorted().map { id ->
            BehaviorComponentDescriptor(id, BehaviorComponentKind.CONDITION, 1, emptyList(), conditionParameters[id] ?: emptyList())
        }
        val actionRows = actions.values.sortedBy { it.id }.map { definition ->
            BehaviorComponentDescriptor(definition.id, BehaviorComponentKind.ACTION, 1,
                definition.channels.map { it.name.lowercase(Locale.ROOT) }.sorted(), actionParameters[definition.id] ?: emptyList())
        }
        return BehaviorComponentCatalog(1, 1, 1, conditionRows, actionRows)
    }

    private fun number(
        name: String, minimum: Double, maximum: Double, description: String,
        integer: Boolean = false, required: Boolean = true, default: Double? = null,
    ): BehaviorParameter.Numeric = BehaviorParameter.Numeric(
        name, required, description, if (integer) BehaviorNumericKind.INTEGER else BehaviorNumericKind.NUMBER,
        minimum, maximum, if (default == null) null else BehaviorNumberDefault.Fixed(default),
    )
}
