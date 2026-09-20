package io.samcnpc.behavior.operation

import io.samcnpc.behavior.api.*

internal object CombatOperationDescriptors {
    private fun fields(tactics: String = "tactics", defaultLeash: Boolean = false) = listOf(
        required("anchor", ref("position")),
        if (defaultLeash) optional("leash", number(1.0, 32.0), "24.0") else required("leash", number(1.0, 32.0)),
        optional("allowPlayers", OperationInput.Flag, "false"), optional("tactics", ref(tactics), "{}"))
    private fun mission() = fields() + listOf(inherited("returnTo", ref("position"), "anchor"))
    private val boundary = relation("MISSION_BOUNDARY", "Return and every waypoint must be inside the fixed anchor/leash sphere.", "anchor", "leash", "returnTo", "route")

    fun values(): List<OperationDescriptor> = listOf(
        descriptor(OperationType.ATTACK, "Engage one explicit UUID; missing targets never authorize substitution.", "TARGET_DEFEATED only for a confirmed defeat of that UUID; TARGET_ENDED is distinct.",
            common(optional("budget", ref("attackBudget"), "{}")) + fields("heldTactics", true) + required("targetUuid", uuid),
            listOf("EXTEND_TIME", "REPLACE", "TACTICS"),
            relation("ATTACK_REPLACEMENT", "Replacement cannot change targetUuid or start NEW_OBJECTIVE; assign a separate task for another enemy.", "targetUuid")),
        descriptor(OperationType.DEFEND, "Guard an explicit subject or a filtered fixed area for a finite duty period.", "DEFENSE_FINISHED after duty and return.",
            common(inherited("budget", ref("defendBudget"), "dutyTicks", offset = 400, target = "ticks")) + mission() +
                listOf(nullable("subjectUuid", uuid), optional("filter", ref("filter"), "{}"), optional("dutyTicks", integer(20, 71600, "ticks"), "1200")),
            listOf("EXTEND_TIME", "TACTICS"), boundary,
            relation("DEFENSE_SUBJECT", "A null subjectUuid requires a nonempty filter. budget.ticks >= dutyTicks + 20.", "subjectUuid", "filter", "dutyTicks", "budget.ticks")),
        descriptor(OperationType.ATTACK_AREA, "Defeat a finite quota matching an explicit filter inside one area.", "AREA_CLEARED: quota of confirmed defeats and physical return.",
            common(optional("budget", ref("areaBudget"), "{}")) + mission() + listOf(required("filter", ref("filter")), required("quota", integer(1, 64, "defeats"))),
            listOf("QUANTITY", "EXTEND_TIME", "TACTICS"), boundary,
            relation("AREA_FILTER", "Explicit nonempty type/tag filter required.", "filter")),
        descriptor(OperationType.PATROL, "Traverse an explicit route for finite rounds with a bounded reaction policy.", "PATROL_FINISHED after rounds, dwell intervals and return.",
            common() + mission() + listOf(required("route", list(ref("position"), 1, 16)), optional("rounds", integer(1, 64, "rounds"), "1"),
                optional("dwellTicks", integer(0, 1200, "ticks"), "20"), optional("reaction", choice(*OperationPatrolReaction.entries.map { it.name }.toTypedArray()), "\"RETALIATE\""),
                nullable("subjectUuid", uuid), nullable("supportTargetUuid", uuid), optional("filter", ref("filter"), "{}")),
            listOf("EXTEND_TIME", "TACTICS"), boundary,
            relation("PATROL_SUBJECT", "subjectUuid is present exactly for PROTECT_SUMMONER; supportTargetUuid exactly for SUPPORT; AREA requires a nonempty filter.", "reaction", "subjectUuid", "supportTargetUuid", "filter")),
    )
}
