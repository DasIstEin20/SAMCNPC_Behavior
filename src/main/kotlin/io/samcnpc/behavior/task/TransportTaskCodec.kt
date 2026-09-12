package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.core.api.*
import net.minecraft.nbt.*

internal object TransportTaskCodec {
    fun keys(hasReturn: Boolean) = setOf("sources", "destinations", "item", "quantity", "anchor", "travelRadius", "keepAtLeast", "sourceKeepAtLeast") + if (hasReturn) setOf("returnTo") else emptySet()
    fun writeDefinition(definition: TransportTaskDefinition, tag: CompoundTag) {
        tag.put("sources", choices(definition.sources)); tag.put("destinations", choices(definition.destinations))
        tag.putString("item", definition.itemId); tag.putInt("quantity", definition.quantity)
        tag.put("anchor", position(definition.anchor)); tag.putDouble("travelRadius", definition.travelRadius)
        tag.putInt("keepAtLeast", definition.keepAtLeast); tag.putInt("sourceKeepAtLeast", definition.sourceKeepAtLeast)
        definition.returnTo?.let { tag.put("returnTo", position(it)) }
    }
    fun readDefinition(tag: CompoundTag, dimension: String, budget: TaskBudget, version: Int) = TransportTaskDefinition(dimension,
        readChoices(tag.getCompound("sources")), readChoices(tag.getCompound("destinations")), tag.getString("item"), tag.int("quantity"),
        readPosition(tag.getCompound("anchor")), tag.double("travelRadius"), tag.int("keepAtLeast"), tag.int("sourceKeepAtLeast"),
        if (tag.contains("returnTo")) readPosition(tag.getCompound("returnTo")) else null, budget, version)

    fun write(state: TransportTaskState) = CompoundTag().apply {
        putString("phase", state.phase.name); put("ledger", ledger(state.ledger))
        state.selected?.let { put("selected", block(it)) }
        state.lastProblem?.let { putString("lastProblem", it.name) }
        put("checkpoints", ListTag().apply { for ((p, shape) in state.checkpoints.toSortedMap(positionOrder)) add(CompoundTag().apply {
            put("position", block(p)); putString("block", shape.blockId); putInt("size", shape.size)
        }) })
    }
    fun read(tag: CompoundTag, task: TaskDefinition): TransportTaskState {
        val definition = CargoRoute.from(task)
        require(tag.allKeys == setOf("phase", "ledger", "checkpoints") +
            (if (tag.contains("selected")) setOf("selected") else emptySet()) + (if (tag.contains("lastProblem")) setOf("lastProblem") else emptySet())) { "unknown/missing transport state" }
        val phase = TransportPhase.entries.firstOrNull { it.name == tag.getString("phase") } ?: throw IllegalArgumentException("unknown transport phase")
        val ledger = readLedger(tag.getCompound("ledger"), definition.itemId)
        require(definition.sources != null || ledger.initialCargo == ledger.initial && ledger.withdrawn == 0) { "carried delivery has unauthorized withdrawals" }
        require(definition.sources == null || ledger.initialCargo == 0 && ledger.legacyCredit == null) { "source transport contains carried delivery credit" }
        require(definition.sources != null || phase != TransportPhase.SOURCE) { "carried delivery cannot seek a source" }
        val selected = if (tag.contains("selected")) readBlock(tag.getCompound("selected")) else null
        val choices = if (phase == TransportPhase.SOURCE) definition.sources?.positions.orEmpty() else definition.destinations.positions
        require(selected == null || phase != TransportPhase.RETURN && selected in choices) { "selected container is outside the authorized stage" }
        require(phase != TransportPhase.RETURN || definition.returnTo != null && ledger.delivered >= definition.quantity && selected == null) { "return lacks completed cargo goal" }
        val checkpoints = linkedMapOf<NpcBlockPosition, ContainerCheckpoint>()
        for (entry in list(tag, "checkpoints", TransportLedger.MAX_ENDPOINTS)) {
            val shape = entry as CompoundTag
            require(shape.allKeys == setOf("position", "block", "size") && validId(shape.getString("block")) && shape.int("size") in 1..64) { "invalid container checkpoint" }
            require(checkpoints.put(readBlock(shape.getCompound("position")), ContainerCheckpoint(shape.getString("block"), shape.int("size"))) == null) { "duplicate container checkpoint" }
        }
        val problem = if (tag.contains("lastProblem")) ContainerTransferProblem.entries.firstOrNull { it.name == tag.getString("lastProblem") }
            ?: throw IllegalArgumentException("unknown transport problem") else null
        return TransportTaskState(ledger, phase, selected, checkpoints, problem)
    }

    private fun ledger(value: TransportLedger) = CompoundTag().apply {
        putString("item", value.itemId); putInt("initial", value.initial); putInt("retained", value.retained)
        putInt("withdrawn", value.withdrawn); putInt("delivered", value.delivered); putInt("incidentalGained", value.incidentalGained)
        putInt("incidentalLost", value.incidentalLost); putInt("cargoLost", value.cargoLost); putInt("transferCount", value.transferCount)
        putInt("initialCargo", value.initialCargo)
        value.legacyCredit?.let { old -> put("legacyCredit", CompoundTag().apply {
            put("position", block(old.destination)); putInt("delivered", old.delivered)
            putInt("sequence", old.receipt.sequence); putInt("npcBefore", old.receipt.npcBefore); putInt("npcAfter", old.receipt.npcAfter)
            putInt("containerBefore", old.receipt.containerBefore); putInt("containerAfter", old.receipt.containerAfter)
        }) }
        putBoolean("uncertain", value.uncertain); put("withdrawals", rows(value.withdrawals)); put("deliveries", rows(value.deliveries))
        value.lastTransfer?.let { t -> put("lastTransfer", CompoundTag().apply {
            put("position", block(t.position)); putString("item", t.itemId); putString("direction", t.direction.name)
            putInt("requested", t.requested); putInt("npcBefore", t.npcBefore); putInt("npcAfter", t.npcAfter)
            putInt("containerBefore", t.containerBefore); putInt("containerAfter", t.containerAfter); putInt("size", t.containerSize); putString("block", t.blockId)
        }) }
    }
    private fun readLedger(tag: CompoundTag, itemId: String): TransportLedger {
        require(tag.allKeys == setOf("item", "initial", "retained", "withdrawn", "delivered", "incidentalGained", "incidentalLost", "cargoLost", "transferCount", "uncertain", "withdrawals", "deliveries", "initialCargo") +
            (if (tag.contains("lastTransfer")) setOf("lastTransfer") else emptySet()) +
            (if (tag.contains("legacyCredit")) setOf("legacyCredit") else emptySet())) { "unknown/missing cargo ledger field" }
        require(tag.getString("item") == itemId) { "cargo ledger belongs to another resource objective" }
        require(tag.contains("uncertain", Tag.TAG_BYTE.toInt()) && tag.getByte("uncertain").toInt() in 0..1) { "invalid cargo certainty" }
        val receipt = if (tag.contains("lastTransfer")) {
            val t = tag.getCompound("lastTransfer")
            require(t.allKeys == setOf("position", "item", "direction", "requested", "npcBefore", "npcAfter", "containerBefore", "containerAfter", "size", "block") && validId(t.getString("block"))) { "unknown/missing cargo receipt" }
            val direction = ContainerTransferDirection.entries.firstOrNull { it.name == t.getString("direction") } ?: throw IllegalArgumentException("unknown transfer direction")
            ContainerTransferObservation(readBlock(t.getCompound("position")), t.getString("item"), direction, t.int("requested"), t.int("npcBefore"), t.int("npcAfter"), t.int("containerBefore"), t.int("containerAfter"), t.int("size"), t.getString("block"))
        } else null
        val legacy = if (tag.contains("legacyCredit")) {
            val old = tag.getCompound("legacyCredit")
            require(old.allKeys == setOf("position", "delivered", "sequence", "npcBefore", "npcAfter", "containerBefore", "containerAfter")) { "invalid legacy delivery credit" }
            LegacyDeliveryCredit(readBlock(old.getCompound("position")), old.int("delivered"), TransferReceipt(old.int("sequence"),
                old.int("npcBefore"), old.int("npcAfter"), old.int("containerBefore"), old.int("containerAfter")))
        } else null
        val value = TransportLedger(itemId, tag.int("initial"), tag.int("retained"), tag.int("withdrawn"), tag.int("delivered"), tag.int("incidentalGained"), tag.int("incidentalLost"),
            tag.int("cargoLost"), readRows(tag, "withdrawals"), readRows(tag, "deliveries"), tag.int("transferCount"), receipt, tag.getBoolean("uncertain"), tag.int("initialCargo"), legacy)
        require(value.valid()) { "cargo ledger violates conservation or bounds" }
        return value
    }
    private fun rows(values: Map<NpcBlockPosition, Int>) = ListTag().apply { for ((p, count) in values.toSortedMap(positionOrder)) add(CompoundTag().apply { put("position", block(p)); putInt("count", count) }) }
    private fun readRows(tag: CompoundTag, key: String): MutableMap<NpcBlockPosition, Int> {
        val values = linkedMapOf<NpcBlockPosition, Int>()
        for (element in list(tag, key, TransportLedger.MAX_ENDPOINTS)) {
            val row = element as CompoundTag
            require(row.allKeys == setOf("position", "count") && row.int("count") in 1..TransportLedger.MAX_COUNT) { "invalid cargo endpoint count" }
            require(values.put(readBlock(row.getCompound("position")), row.int("count")) == null) { "duplicate cargo endpoint" }
        }
        return values
    }
    private fun choices(value: ContainerChoices) = CompoundTag().apply { putString("preference", value.preference.name); put("positions", ListTag().apply { value.positions.forEach { add(block(it)) } }) }
    private fun readChoices(tag: CompoundTag): ContainerChoices {
        require(tag.allKeys == setOf("preference", "positions")) { "invalid container choices" }
        val preference = ContainerPreference.entries.firstOrNull { it.name == tag.getString("preference") } ?: throw IllegalArgumentException("unknown container preference")
        return ContainerChoices(list(tag, "positions", 8).map { readBlock(it as CompoundTag) }, preference)
    }
    private fun block(p: NpcBlockPosition) = CompoundTag().apply { putInt("x", p.x); putInt("y", p.y); putInt("z", p.z) }
    private fun readBlock(tag: CompoundTag): NpcBlockPosition {
        require(tag.allKeys == setOf("x", "y", "z")) { "invalid block position" }
        val p = NpcBlockPosition(tag.int("x"), tag.int("y"), tag.int("z"))
        require(p.x in -29_999_984..29_999_984 && p.z in -29_999_984..29_999_984 && p.y in -2048..2048) { "unsupported block coordinates" }
        return p
    }
    private fun position(p: NpcPosition) = CompoundTag().apply { putDouble("x", p.x); putDouble("y", p.y); putDouble("z", p.z) }
    private fun readPosition(tag: CompoundTag): NpcPosition { require(tag.allKeys == setOf("x", "y", "z")); return NpcPosition(tag.double("x"), tag.double("y"), tag.double("z")) }
    private fun list(tag: CompoundTag, key: String, max: Int): ListTag {
        val values = tag.get(key) as? ListTag ?: throw IllegalArgumentException("missing bounded list $key")
        require(values.size <= max && (values.isEmpty() || values.elementType == Tag.TAG_COMPOUND)) { "invalid/oversized list $key" }; return values
    }
    private val positionOrder = compareBy<NpcBlockPosition> { it.x }.thenBy { it.y }.thenBy { it.z }
    private val id = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")
    private fun validId(value: String) = value.length <= 256 && id.matches(value)
    private fun CompoundTag.int(key: String): Int { require(contains(key, Tag.TAG_INT.toInt())) { "missing integer $key" }; return getInt(key) }
    private fun CompoundTag.double(key: String): Double { require(contains(key, Tag.TAG_DOUBLE.toInt()) && getDouble(key).isFinite()) { "invalid double $key" }; return getDouble(key) }
}
