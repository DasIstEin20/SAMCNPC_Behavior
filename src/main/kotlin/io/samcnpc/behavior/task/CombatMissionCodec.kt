package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcEntityTypeFilter
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag

internal object CombatMissionCodec {
    private val common = setOf("anchor", "leash", "types", "tags", "returnTo", "allowPlayers", "tactics")
    fun keys(id: String, subject: Boolean, support: Boolean): Set<String> = common + when (id) {
        DefendTaskDefinition.ID -> setOf("dutyTicks") + if (subject) setOf("subject") else emptySet()
        AreaAttackTaskDefinition.ID -> setOf("quota")
        PatrolTaskDefinition.ID -> setOf("route", "rounds", "dwellTicks", "reaction") +
            (if (subject) setOf("subject") else emptySet()) + (if (support) setOf("supportTarget") else emptySet())
        else -> error("unknown combat mission")
    }
    fun write(definition: CombatMissionDefinition, tag: CompoundTag) {
        tag.put("anchor", position(definition.anchor)); tag.putDouble("leash", definition.leash)
        tag.put("returnTo", position(definition.returnTo)); tag.putBoolean("allowPlayers", definition.allowPlayers)
        tag.put("tactics", CombatTacticsCodec.write(definition.tactics))
        tag.put("types", strings(definition.filter.typeIds)); tag.put("tags", strings(definition.filter.tagIds))
        when (definition) {
            is DefendTaskDefinition -> { tag.putInt("dutyTicks", definition.dutyTicks); definition.subjectUuid?.let { tag.putUUID("subject", it) } }
            is AreaAttackTaskDefinition -> tag.putInt("quota", definition.quota)
            is PatrolTaskDefinition -> {
                val route = ListTag(); definition.route.forEach { route.add(position(it)) }; tag.put("route", route)
                tag.putInt("rounds", definition.rounds); tag.putInt("dwellTicks", definition.dwellTicks)
                tag.putString("reaction", definition.reaction.name); definition.subjectUuid?.let { tag.putUUID("subject", it) }
                definition.supportTargetUuid?.let { tag.putUUID("supportTarget", it) }
            }
        }
    }
    fun read(tag: CompoundTag, id: String, dimension: String, budget: TaskBudget, version: Int): CombatMissionDefinition {
        val anchor = readPosition(tag.getCompound("anchor")); val returnTo = readPosition(tag.getCompound("returnTo"))
        val leash = tag.double("leash")
        val filter = NpcEntityTypeFilter.of(readStrings(tag, "types"), readStrings(tag, "tags"))
        require(tag.contains("allowPlayers", Tag.TAG_BYTE.toInt()) && tag.getByte("allowPlayers").toInt() in 0..1) { "invalid player permission" }
        val players = tag.getBoolean("allowPlayers"); val tactics = CombatTacticsCodec.read(tag.getCompound("tactics"))
        val subject = if (tag.contains("subject")) { require(tag.hasUUID("subject")); tag.getUUID("subject") } else null
        return when (id) {
            DefendTaskDefinition.ID -> DefendTaskDefinition(dimension, anchor, leash, subject, filter, tag.int("dutyTicks"), returnTo, players, tactics, budget, version)
            AreaAttackTaskDefinition.ID -> AreaAttackTaskDefinition(dimension, anchor, leash, filter, tag.int("quota"), returnTo, players, tactics, budget, version)
            PatrolTaskDefinition.ID -> {
                val route = list(tag, "route", Tag.TAG_COMPOUND, 16).map { readPosition(it as CompoundTag) }
                val support = if (tag.contains("supportTarget")) { require(tag.hasUUID("supportTarget")); tag.getUUID("supportTarget") } else null
                val reaction = PatrolReaction.entries.firstOrNull { it.name == tag.getString("reaction") } ?: throw IllegalArgumentException("invalid patrol reaction")
                PatrolTaskDefinition(dimension, anchor, leash, route, tag.int("rounds"), tag.int("dwellTicks"), reaction, subject, support, filter, returnTo, players, tactics, budget, version)
            }
            else -> error("unknown mission")
        }
    }
    fun validateState(definition: TaskDefinition, state: CombatTaskState) {
        require(!state.supportFinished || definition is PatrolTaskDefinition && definition.reaction == PatrolReaction.SUPPORT) { "unexpected support completion" }
        if (definition !is CombatMissionDefinition) {
            require(state.dutyTicks == 0 && state.defeatedTargets.isEmpty() && state.patrolIndex == 0 && state.patrolRounds == 0 && !state.returning && !state.waypointReached) { "exact attack contains mission progress" }
            require(definition is AttackTaskDefinition && state.selectedTarget == definition.targetUuid) { "exact attack target differs from tactical target" }
            return
        }
        require(state.defeatedTargets.size <= 64) { "mission kill ledger exceeded quota" }
        if (definition is DefendTaskDefinition) require(state.dutyTicks <= definition.dutyTicks) { "defense gained duty time" }
        else require(state.dutyTicks == 0) { "unexpected duty time" }
        if (definition is PatrolTaskDefinition) {
            require(state.patrolIndex in definition.route.indices && state.patrolRounds <= definition.rounds && state.dwellTicks <= definition.dwellTicks) { "invalid patrol progress" }
        } else require(state.patrolIndex == 0 && state.patrolRounds == 0 && state.dwellTicks == 0 && !state.waypointReached) { "unexpected patrol progress" }
    }
    fun validateCompletion(definition: CombatMissionDefinition, frame: TaskFrame, reason: TaskReason, observed: NpcPosition?) {
        val state = checkNotNull(frame.combat)
        require(state.returning && state.selectedTarget == null && observed != null && TaskNavigator.distanceSquared(observed, definition.returnTo) <= 1.0) { "mission has no observed physical return" }
        when (definition) {
            is DefendTaskDefinition -> require(reason == TaskReason.DEFENSE_FINISHED && state.dutyTicks == 0) { "defense duty unfinished" }
            is AreaAttackTaskDefinition -> require(reason == TaskReason.AREA_CLEARED && state.defeatedTargets.size >= definition.quota) { "area quota lacks confirmed defeats" }
            is PatrolTaskDefinition -> require(reason == TaskReason.PATROL_FINISHED && state.patrolRounds == definition.rounds) { "patrol rounds unfinished" }
        }
    }
    private fun position(p: NpcPosition) = CompoundTag().apply { putDouble("x", p.x); putDouble("y", p.y); putDouble("z", p.z) }
    private fun readPosition(tag: CompoundTag): NpcPosition {
        require(tag.allKeys == setOf("x", "y", "z")) { "unknown/missing position coordinate" }
        return NpcPosition(tag.double("x"), tag.double("y"), tag.double("z"))
    }
    private fun strings(values: Set<String>) = ListTag().apply { values.sorted().forEach { add(StringTag.valueOf(it)) } }
    private fun readStrings(tag: CompoundTag, key: String): Set<String> {
        val values = list(tag, key, Tag.TAG_STRING, 32).map { it.asString }
        require(values.distinct().size == values.size) { "duplicate entity filter" }
        return values.toSet()
    }
    private fun list(tag: CompoundTag, key: String, type: Byte, max: Int): ListTag {
        val list = tag.get(key) as? ListTag ?: throw IllegalArgumentException("missing list $key")
        require(list.size <= max && (list.isEmpty() || list.elementType == type)) { "invalid bounded list $key" }; return list
    }
    private fun CompoundTag.int(key: String): Int { require(contains(key, Tag.TAG_INT.toInt())) { "missing integer $key" }; return getInt(key) }
    private fun CompoundTag.double(key: String): Double { require(contains(key, Tag.TAG_DOUBLE.toInt()) && getDouble(key).isFinite()) { "invalid double $key" }; return getDouble(key) }
}
