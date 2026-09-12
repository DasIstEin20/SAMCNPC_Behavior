package io.samcnpc.behavior.runtime

import com.google.gson.JsonObject
import io.samcnpc.behavior.registry.exactLongOrNull
import io.samcnpc.behavior.combat.CombatTargetSelector
import io.samcnpc.behavior.model.ActionHandler
import io.samcnpc.behavior.model.BehaviorChannel
import io.samcnpc.behavior.registry.ActionDefinition
import io.samcnpc.core.api.*
import java.util.UUID

/** Transient reaction identity and UUID-only target state, never Entity references. */
object BehaviorTargetMemory {
    internal data class Settings(val leash: Double = 24.0, val durationTicks: Int = 600, val allowPlayers: Boolean = false)
    private data class Selection(val uuid: UUID, val area: CombatTargetSelector.Area, val started: Long, val settings: Settings)
    private class Memory(var handledDamage: UUID? = null) {
        var selection: Selection? = null
        var detail: String? = null
    }
    private val memories = mutableMapOf<UUID, Memory>()

    internal fun definition() = ActionDefinition("samcnpc:set_attack_target_from_recent_attacker", setOf(BehaviorChannel.COMBAT), ::validate) { args ->
        val settings = Settings(args.get("leash")?.asDouble ?: 24.0, args.get("durationTicks")?.asInt ?: 600,
            args.get("allowPlayers")?.asBoolean ?: false)
        ActionHandler { _, world, context -> acquireFromRecentDamage(context.snapshot, world, settings) }
    }

    fun hasUnhandledDamage(snapshot: NpcSnapshot): Boolean {
        val id = snapshot.lastDamageEventId ?: return false
        return snapshot.lastDamageAgeTicks in 0L..100L && memories[snapshot.npcUuid]?.handledDamage != id
    }

    internal fun acquireFromRecentDamage(snapshot: NpcSnapshot, world: NpcWorldView, settings: Settings = Settings()): NpcActionResult {
        if (!hasUnhandledDamage(snapshot)) return NpcActionResult.running("no new accepted damage event")
        val memory = memories.getOrPut(snapshot.npcUuid) { Memory() }
        memory.handledDamage = snapshot.lastDamageEventId
        if (memory.selection != null) return NpcActionResult.running("new hit consumed; current target and deadline retained")
        val uuid = snapshot.lastDamageSourceEntityUuid ?: return NpcActionResult.rejected("damage has no observable attacker")
        val area = CombatTargetSelector.Area(snapshot.position, settings.leash)
        val target = CombatTargetSelector.exact(snapshot, world, uuid, area, settings.allowPlayers)
        if (target == null) {
            memory.detail = "retaliation ignored: attacker is unavailable, unseen, allied, disallowed or outside the leash"
            return NpcActionResult.succeeded(checkNotNull(memory.detail))
        }
        memory.selection = Selection(target.uuid, area, snapshot.gameTime, settings)
        memory.detail = "retaliation acquired ${target.uuid}; leash=${settings.leash} deadline=${settings.durationTicks} ticks"
        return NpcActionResult.succeeded(checkNotNull(memory.detail))
    }

    fun targetFor(npcUuid: UUID): UUID? = memories[npcUuid]?.selection?.uuid
    fun diagnostic(npcUuid: UUID): String? = memories[npcUuid]?.detail

    fun clear(npcUuid: UUID, detail: String = "behavior target cleared"): NpcActionResult {
        val memory = memories[npcUuid]
        if (memory != null) { memory.selection = null; memory.detail = detail }
        return NpcActionResult.succeeded(detail)
    }

    /** Explicit observation/update phase. Continued hits cannot replace a valid target or restart its budget. */
    fun refresh(snapshot: NpcSnapshot, world: NpcWorldView): NpcEntityObservation? {
        val memory = memories[snapshot.npcUuid] ?: return null
        val selection = memory.selection ?: return null
        memory.handledDamage = snapshot.lastDamageEventId
        val elapsed = snapshot.gameTime - selection.started
        val target = world.observeEntity(selection.uuid)
        val detail = when {
            elapsed < 0 || elapsed >= selection.settings.durationTicks -> "retaliation ended: original chase deadline reached"
            !selection.area.contains(snapshot.position) || (target != null && !selection.area.contains(target.position)) -> "retaliation ended: chase leash reached"
            target == null || !target.alive -> "retaliation ended: target died or became unavailable"
            !CombatTargetSelector.eligible(snapshot, target, selection.area, selection.settings.allowPlayers) -> "retaliation ended: target visibility, relationship or permission changed"
            else -> null
        }
        if (detail != null) { clear(snapshot.npcUuid, detail); return null }
        return target
    }

    /** Assignment/reload baselines old damage; only a later accepted hit may start a new reaction. */
    fun reset(snapshot: NpcSnapshot) { memories[snapshot.npcUuid] = Memory(snapshot.lastDamageEventId) }
    fun remove(npcUuid: UUID) { memories.remove(npcUuid) }
    fun clearAll() { memories.clear() }

    private fun validate(args: JsonObject): String? {
        if (args.keySet().any { it !in setOf("leash", "durationTicks", "allowPlayers") }) return "accepts only leash, durationTicks and allowPlayers"
        val leash = args.get("leash")
        if (leash != null && (!leash.isJsonPrimitive || !leash.asJsonPrimitive.isNumber || !leash.asDouble.isFinite() || leash.asDouble !in 1.0..32.0)) return "leash must be a finite number in [1, 32]"
        val ticks = args.get("durationTicks")
        if (ticks != null) {
            val exact = ticks.exactLongOrNull()
            if (exact == null || exact !in 20L..2400L) return "durationTicks must be an integer in [20, 2400]"
        }
        val players = args.get("allowPlayers")
        if (players != null && (!players.isJsonPrimitive || !players.asJsonPrimitive.isBoolean)) return "allowPlayers must be a boolean"
        return null
    }
}
