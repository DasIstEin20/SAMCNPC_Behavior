package io.samcnpc.behavior.operation

import io.samcnpc.behavior.api.*

internal object BasicOperationDescriptors {
    fun values(): List<OperationDescriptor> = listOf(
        descriptor(OperationType.FIELD_PREPARATION, "Hoe an explicit soil plane using a carried hoe. No sowing, harvest, irrigation or clearing.", "FIELD_PREPARED: actual farmland verified after optional return.",
            common()+travel(32.0)+listOf(required("area",ref("area"))),listOf("EXTEND_TIME","TACTICS","REACTION"),
            localEndpoints,relation("SOIL_PLANE","Area denotes soil blocks: one y level, bounding footprint <=64 cells, nonempty after exclusions. Soil/anchor/return pairwise within58 blocks.","area","anchor","returnTo")),
        descriptor(OperationType.NAVIGATE, "Travel to one supplied position.", "ARRIVED after physical arrival.",
            common() + listOf(required("destination", ref("position")), optional("speed", number(0.1, 1.5, "speed_multiplier"), "1.0"),
                optional("arrivalDistance", number(0.25, 2.0), "0.75")),
            listOf("EXTEND_TIME", "REPLACE", "TACTICS", "REACTION", "LOGISTICS")),
        descriptor(OperationType.DELIVER, "Deliver carried stock to one recipient, preserving a reserve.", "DELIVERED: confirmed transferred items.",
            common() + listOf(required("destination", ref("block")), required("itemId", id), required("quantity", integer(1, 2304, "items")),
                required("anchor", ref("position")), optional("keepAtLeast", integer(0, 2304, "items"), "0")),
            listOf("QUANTITY", "RECIPIENTS", "EXTEND_TIME", "REPLACE", "TACTICS", "REACTION", "LOGISTICS"),
            relation("DELIVERY_ANCHOR", "Recipient center must be within 60 blocks of anchor.", "anchor", "destination")),
        descriptor(OperationType.TRANSPORT, "Withdraw authorized stock and transport it to authorized recipients.", "DELIVERED: confirmed transferred items.",
            common() + travel() + listOf(required("sources", ref("containers")), required("destinations", ref("containers")), required("itemId", id),
                required("quantity", integer(1, 2304, "items")), optional("keepAtLeast", integer(0, 2304, "items"), "0"),
                optional("sourceKeepAtLeast", integer(0, 2304, "items"), "0")),
            listOf("QUANTITY", "RECIPIENTS", "SOURCES", "EXTEND_TIME", "REPLACE", "TACTICS", "REACTION", "LOGISTICS"),
            localEndpoints, relation("TRANSPORT_ENDPOINTS", "Source and destination lists are disjoint; all endpoints/anchor/return pairwise within 60 blocks.", "sources", "destinations", "anchor", "returnTo")),
        descriptor(OperationType.MACHINE, "Feed and collect one supplied machine through explicit faces and slots; the machine processes items.", "MACHINE_FINISHED: supplied input and confirmed output contract.",
            common() + travel(32.0) + listOf(required("feeds", list(ref("port"), 1, 4)), required("output", ref("port")),
                optional("pollTicks", integer(5, 200, "ticks"), "20"), optional("noProgressTicks", integer(5, 72000, "ticks"), "1200")),
            listOf("EXTEND_TIME", "TACTICS", "REACTION"),
            localEndpoints, relation("MACHINE_PORTS", "All ports address the output block in dimensionId. Feed endpoint/slot pairs are unique and differ from output; input item IDs differ from output; total feed <= 2304.", "feeds", "output", "dimensionId"),
            relation("MACHINE_TIME", "pollTicks <= noProgressTicks <= budget.ticks.", "pollTicks", "noProgressTicks", "budget.ticks")),
        descriptor(OperationType.FISH, "Fish at supplied water from a supplied standing position.", "FISHING_FINISHED: confirmed catches, with bounded collection and optional return.",
            common() + listOf(required("water", ref("block")), required("standing", ref("position")), required("catches", integer(1, 64, "catches")),
                required("anchor", ref("position")), optional("travelRadius", number(4.0, 64.0), "32.0"),
                inherited("returnTo", ref("position"), "anchor", nullable = true), optional("pickupWaitTicks", integer(40, 1200, "ticks"), "240")),
            listOf("EXTEND_TIME", "TACTICS", "REACTION"),
            localEndpoints, relation("FISHING_REACH", "Standing point is within 9 blocks of water center; pickupWaitTicks <= budget.ticks. Maximum casts is max(8,catches*4).", "standing", "water", "pickupWaitTicks", "budget.ticks", "catches")),
        descriptor(OperationType.EXPLORE, "Explore a fixed bounded envelope using legal local observations.", "EXPLORATION_FINISHED: finite exploration report, not omniscient terrain knowledge.",
            common() + listOf(required("anchor", ref("position")), optional("radius", integer(8, 96, "blocks"), "32"),
                optional("cellStep", integer(4, 8, "blocks"), "4"), optional("maxCells", integer(1, 64, "cells"), "32"),
                optional("verticalRange", integer(1, 16, "blocks"), "12"), optional("chunkBudget", integer(9, 256, "chunks"), "64"),
                optional("heading", integer(0, 3, "quarter_turns"), "0")),
            listOf("EXTEND_TIME", "TACTICS", "REACTION"),
            relation("EXPLORER_ENVELOPE", "Horizontal bounds stay inside +/-29999984. chunkBudget covers the chunk footprint plus Core margin: (floor((x+r)/16)-floor((x-r)/16)+3) times the corresponding z span.", "anchor", "radius", "chunkBudget")),
        descriptor(OperationType.INVENTORY, "Perform one finite supply, unload, pickup or whole-container collection request and return.", "INVENTORY_FINISHED or INVENTORY_INCOMPLETE after measured item transfers.",
            common(optional("budget", ref("inventoryBudget"), "{}")) + listOf(required("work", ref("inventoryWork")), required("anchor", ref("position")),
                inherited("returnTo", ref("position"), "anchor"), optional("travelRadius", number(4.0, 64.0), "16.0"),
                optional("workTicks", integer(20, 36000, "ticks"), "600"), optional("maxSteps", integer(1, 128, "steps"), "128")),
            listOf("EXTEND_TIME", "TACTICS", "REACTION"),
            localEndpoints, relation("INVENTORY_RETURN_TIME", "workTicks <= budget.ticks - 20; containers and return are within travelRadius.", "workTicks", "budget.ticks", "work", "returnTo")),
    )
}

internal fun descriptor(type: OperationType, description: String, completion: String, fields: List<OperationField>,
    amendments: List<String>, vararg relations: OperationRelation) =
    OperationDescriptor(type, description, OperationInput.Record(fields, relations.toList()), completion, amendments)
