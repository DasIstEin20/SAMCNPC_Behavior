package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTactics
import io.samcnpc.behavior.combat.CombatWeaponAllowance
import io.samcnpc.behavior.combat.CombatWeaponPreference
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

internal object CombatTacticsCodec {
    private val POLICY_KEYS = setOf("preference", "allowed", "armor", "shield", "heal", "retreat", "return", "minimumDistance", "maximumDistance")
    fun write(policy: CombatTactics) = CompoundTag().apply {
        putString("preference", policy.preference.name); putString("allowed", policy.allowed.name)
        putBoolean("armor", policy.equipArmor); putBoolean("shield", policy.useShield); putBoolean("heal", policy.heal)
        putDouble("retreat", policy.retreatAt); putDouble("return", policy.returnAt)
        putDouble("minimumDistance", policy.rangedMinDistance); putDouble("maximumDistance", policy.rangedMaxDistance)
    }
    fun read(tag: CompoundTag): CombatTactics {
        require(tag.allKeys == POLICY_KEYS) { "unknown/missing combat tactics parameter" }
        val preference = CombatWeaponPreference.entries.firstOrNull { it.name == tag.getString("preference") }
            ?: throw IllegalArgumentException("unknown weapon preference")
        val allowed = CombatWeaponAllowance.entries.firstOrNull { it.name == tag.getString("allowed") }
            ?: throw IllegalArgumentException("unknown weapon allowance")
        val result = CombatTactics(preference, allowed, tag.boolean("armor"), tag.boolean("shield"), tag.boolean("heal"),
            tag.double("retreat"), tag.double("return"), tag.double("minimumDistance"), tag.double("maximumDistance"))
        require(result.validationProblem() == null) { result.validationProblem().orEmpty() }
        return result
    }
    fun writeState(state: CombatTaskState) = CompoundTag().apply {
        putBoolean("retreating", state.retreating); putInt("recoveryTicks", state.recoveryTicks)
        putInt("healingCooldown", state.healingCooldown); putInt("healingUses", state.healingUses)
        state.healingBaseline?.let { putDouble("healingBaseline", it) }
        putBoolean("observedHealing", state.observedHealing); putBoolean("attackSubmitted", state.attackSubmitted)
        state.selectedTarget?.let { putUUID("selectedTarget", it) }
        val defeated = ListTag()
        for (uuid in state.defeatedTargets.sortedBy { it.toString() }) defeated.add(CompoundTag().apply { putUUID("uuid", uuid) })
        put("defeated", defeated); putInt("patrolIndex", state.patrolIndex); putInt("patrolRounds", state.patrolRounds)
        putInt("dwellTicks", state.dwellTicks); putBoolean("returning", state.returning)
        putBoolean("supportFinished", state.supportFinished)
        putInt("dutyTicks", state.dutyTicks); putBoolean("waypointReached", state.waypointReached)
        state.consumedThreatTick?.let { putLong("consumedThreatTick", it) }
        state.consumedThreatAttacker?.let { putUUID("consumedThreatAttacker", it) }
    }
    fun readState(tag: CompoundTag): CombatTaskState {
        val required = setOf("retreating", "recoveryTicks", "healingCooldown", "healingUses", "observedHealing", "attackSubmitted",
            "defeated", "patrolIndex", "patrolRounds", "dwellTicks", "returning", "dutyTicks", "waypointReached", "supportFinished")
        require(tag.allKeys.containsAll(required) && (tag.allKeys - required - setOf("healingBaseline", "selectedTarget", "consumedThreatTick", "consumedThreatAttacker")).isEmpty()) { "unknown/missing tactical state" }
        val list = tag.get("defeated") as? ListTag ?: throw IllegalArgumentException("missing combat kill ledger")
        require(list.size <= 64 && (list.isEmpty() || list.elementType == Tag.TAG_COMPOUND)) { "invalid combat kill ledger" }
        val defeated = linkedSetOf<java.util.UUID>()
        for (element in list) {
            val item = element as CompoundTag
            require(item.allKeys == setOf("uuid") && item.hasUUID("uuid") && defeated.add(item.getUUID("uuid"))) { "invalid/duplicate defeated target" }
        }
        val baseline = if (tag.contains("healingBaseline")) tag.double("healingBaseline") else null
        require(baseline == null || baseline.isFinite() && baseline in 0.0..1.0) { "invalid observed healing baseline" }
        val target = if (tag.contains("selectedTarget")) {
            require(tag.hasUUID("selectedTarget")) { "invalid selected target" }; tag.getUUID("selectedTarget")
        } else null
        val threatTick = if (tag.contains("consumedThreatTick")) {
            require(tag.contains("consumedThreatTick", Tag.TAG_LONG.toInt()) && tag.getLong("consumedThreatTick") >= 0) { "invalid consumed threat tick" }
            tag.getLong("consumedThreatTick")
        } else null
        val threatAttacker = if (tag.contains("consumedThreatAttacker")) {
            require(tag.hasUUID("consumedThreatAttacker")) { "invalid consumed attacker" }; tag.getUUID("consumedThreatAttacker")
        } else null
        require((threatTick != null) == (threatAttacker != null)) { "incomplete consumed threat identity" }
        require(!tag.boolean("attackSubmitted") || target != null) { "attack has no selected target identity" }
        return CombatTaskState(tag.boolean("retreating"), tag.int("recoveryTicks", 0..400), tag.int("healingCooldown", 0..200),
            baseline, tag.int("healingUses", 0..16), tag.boolean("observedHealing"), target, tag.boolean("attackSubmitted"), defeated,
            tag.int("patrolIndex", 0..15), tag.int("patrolRounds", 0..64), tag.int("dwellTicks", 0..1200), tag.boolean("returning"), tag.int("dutyTicks", 0..71600),
            tag.boolean("waypointReached"), threatTick, threatAttacker, tag.boolean("supportFinished"))
    }
    private fun CompoundTag.int(key: String, range: IntRange): Int {
        require(contains(key, Tag.TAG_INT.toInt()) && getInt(key) in range) { "invalid bounded integer $key" }; return getInt(key)
    }
    private fun CompoundTag.double(key: String): Double {
        require(contains(key, Tag.TAG_DOUBLE.toInt()) && getDouble(key).isFinite()) { "invalid double $key" }; return getDouble(key)
    }
    private fun CompoundTag.boolean(key: String): Boolean {
        require(contains(key, Tag.TAG_BYTE.toInt()) && getByte(key).toInt() in 0..1) { "invalid boolean $key" }; return getBoolean(key)
    }
}