package io.samcnpc.behavior.operation

import com.google.gson.JsonObject
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import java.util.UUID

/** Only normalized, structurally checked trees enter these explicit constructors. */
internal fun JsonObject.text(name: String): String = get(name).asString
internal fun JsonObject.int(name: String): Int = get(name).asBigDecimal.intValueExact()
internal fun JsonObject.number(name: String): Double = get(name).asDouble
internal fun JsonObject.flag(name: String): Boolean = get(name).asBoolean
internal fun JsonObject.obj(name: String): JsonObject = getAsJsonObject(name)
internal fun JsonObject.maybe(name: String): JsonObject? = get(name).let { if (it.isJsonNull) null else it.asJsonObject }
internal fun JsonObject.uuid(name: String): UUID = UUID.fromString(text(name))
internal fun JsonObject.optionalUuid(name: String): UUID? = if (get(name).isJsonNull) null else uuid(name)

internal object OperationValueReader {
    fun position(p: JsonObject) = NpcPosition(p.number("x"), p.number("y"), p.number("z"))
    fun block(p: JsonObject) = NpcBlockPosition(p.int("x"), p.int("y"), p.int("z"))
    fun budget(p: JsonObject) = OperationBudget(p.int("ticks"), p.int("attempts"), p.int("backoffTicks"))
    fun containers(p: JsonObject) = OperationContainers(p.getAsJsonArray("positions").map { block(it.asJsonObject) },
        OperationContainerPreference.valueOf(p.text("preference")))
    fun resources(p: com.google.gson.JsonElement) = OperationResourceIds(p.asJsonArray.map { it.asString })
    fun box(p: JsonObject) = OperationWorkBox(block(p.obj("min")), block(p.obj("max")))
    fun area(p: JsonObject) = OperationWorkArea(box(p.obj("bounds")), p.getAsJsonArray("exclusions").map { box(it.asJsonObject) })
    fun filter(p: JsonObject) = NpcEntityTypeFilter.of(p.getAsJsonArray("typeIds").map { it.asString }.toSet(),
        p.getAsJsonArray("tagIds").map { it.asString }.toSet())
    fun endpoint(p: JsonObject) = NpcContainerEndpoint(p.text("dimensionId"), block(p.obj("position")),
        if (p.get("side").isJsonNull) null else NpcBlockFace.valueOf(p.text("side")))
    fun port(p: JsonObject) = OperationMachinePort(endpoint(p.obj("endpoint")), p.int("slot"), p.text("itemId"), p.int("quantity"))
    fun tactics(p: JsonObject) = OperationCombatTactics(OperationWeaponPreference.valueOf(p.text("preference")),
        OperationWeaponAllowance.valueOf(p.text("allowed")), p.flag("equipArmor"), p.flag("useShield"), p.flag("heal"),
        p.number("retreatAt"), p.number("returnAt"), p.number("rangedMinDistance"), p.number("rangedMaxDistance"))

    fun supply(p: JsonObject) = OperationInventoryWork.Supply(p.getAsJsonArray("needs").map { element ->
        val item = element.asJsonObject
        OperationStockNeed(item.text("itemId"), item.int("minimum"), item.int("target"), item.int("sourceReserve"))
    }, containers(p.obj("sources")))
    fun unload(p: JsonObject) = OperationInventoryWork.Unload(p.getAsJsonArray("reserves").map { element ->
        val item = element.asJsonObject
        OperationItemReserve(item.text("itemId"), item.int("keep"))
    }, containers(p.obj("destinations")))
    fun pickup(p: JsonObject) = OperationInventoryWork.Pickup(p.getAsJsonArray("itemIds").map { it.asString }, p.number("radius"), p.int("maxItems"))
    fun inventory(p: JsonObject): OperationInventoryWork = when (p.text("kind")) {
        "SUPPLY" -> supply(p)
        "UNLOAD" -> unload(p)
        "PICKUP" -> pickup(p)
        else -> error("Validated inventory discriminator is not mapped")
    }
    fun reaction(p: JsonObject) = OperationReactionPolicy(OperationReactionMode.valueOf(p.text("mode")),
        p.number("leash"), p.int("durationTicks"), p.int("cooldownTicks"), p.flag("allowPlayers"), tactics(p.obj("tactics")),
        p.maybe("anchor")?.let(::position), p.optionalUuid("subjectUuid"), filter(p.obj("filter")))
    fun logistics(p: JsonObject) = OperationLogisticsPolicy(p.maybe("anchor")?.let(::position), p.maybe("supply")?.let(::supply),
        p.maybe("unload")?.let(::unload), p.maybe("pickup")?.let(::pickup), p.number("travelRadius"),
        p.int("workTicks"), p.int("durationTicks"), p.int("cooldownTicks"), p.int("maxSteps"))
}
