package io.samcnpc.behavior.operation

import io.samcnpc.behavior.api.*

internal object OperationChangeShapes {
    fun shapes(): Map<String, OperationInput> = linkedMapOf(
        "reaction" to record(listOf(
            optional("mode", choice(*OperationReactionMode.entries.map { it.name }.toTypedArray()), "\"PASSIVE\""),
            optional("leash", number(1.0, 32.0), "24.0"), optional("durationTicks", integer(20, 2400, "ticks"), "600"),
            optional("cooldownTicks", integer(20, 200, "ticks"), "40"), optional("allowPlayers", OperationInput.Flag, "false"),
            optional("tactics", ref("heldTactics"), "{}"), nullable("anchor", ref("position")), nullable("subjectUuid", uuid),
            optional("filter", ref("filter"), "{}")),
            relation("REACTION_SUBJECT", "Subject exactly for PROTECT_SUMMONER/PROTECT_UNIT; anchor exactly for protection/AREA; nonempty filter exactly for AREA.", "mode", "anchor", "subjectUuid", "filter")),
        "logistics" to record(listOf(nullable("anchor", ref("position")), nullable("supply", ref("supply")),
            nullable("unload", ref("unload")), nullable("pickup", ref("pickup")),
            optional("travelRadius", number(4.0, 64.0), "16.0"), optional("workTicks", integer(20, 36000, "ticks"), "600"),
            optional("durationTicks", integer(40, 72000, "ticks"), "1200"), optional("cooldownTicks", integer(20, 6000, "ticks"), "200"),
            optional("maxSteps", integer(1, 128, "steps"), "128")),
            relation("LOGISTICS_ENABLED", "Anchor present exactly when some side work is supplied. Each work validates as inventory work. workTicks <= durationTicks - 20. For a shared item, unload keep >= supply target.", "anchor", "supply", "unload", "pickup", "workTicks", "durationTicks")),
    )
    fun changes(): Map<String, OperationInput> = linkedMapOf(
        "QUANTITY" to record(required("amount", integer(1, 2304, "operation_counting_basis")), optional("mode", choice("TOTAL", "ADD"), "\"TOTAL\"")),
        "RECIPIENTS" to record(required("containers", ref("containers"))),
        "SOURCES" to record(nullable("containers", ref("containers"))),
        "EXTEND_TIME" to record(required("ticks", integer(1, 72000, "ticks"))),
        "REPLACE" to record(listOf(required("order", ref("orderDocument")), optional("objective", choice("PRESERVE", "NEW_OBJECTIVE"), "\"PRESERVE\"")),
            relation("REPLACE_INVARIANTS", "Same operation/dimension; budget unchanged. Resource/work/counting changes require NEW_OBJECTIVE where supported. Existing effects remain accounted. Task-specific restrictions and at most 8 archived objectives apply.", "order", "objective")),
        "TACTICS" to record(required("tactics", ref("tactics"))),
        "REACTION" to record(required("policy", ref("reaction"))),
        "LOGISTICS" to record(required("policy", ref("logistics"))),
    )
}
