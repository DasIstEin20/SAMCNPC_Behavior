package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatTactics
import io.samcnpc.core.api.NpcEntityTypeFilter
import io.samcnpc.core.api.NpcPosition
import java.util.UUID
import kotlin.math.abs

internal enum class TaskReactionMode { PASSIVE, RETALIATE, PROTECT_SUMMONER, PROTECT_UNIT, AREA }

/** Optional work interruption policy; fixed protection/area bounds never follow a moving target. */
internal data class TaskReactionPolicy(
    val mode: TaskReactionMode = TaskReactionMode.PASSIVE,
    val leash: Double = 24.0,
    val durationTicks: Int = 600,
    val cooldownTicks: Int = 40,
    val allowPlayers: Boolean = false,
    val tactics: CombatTactics = CombatTactics.LEGACY,
    val anchor: NpcPosition? = null,
    val subjectUuid: UUID? = null,
    val filter: NpcEntityTypeFilter = NpcEntityTypeFilter.ANY,
) {
    val protectsSubject: Boolean get() = mode == TaskReactionMode.PROTECT_SUMMONER || mode == TaskReactionMode.PROTECT_UNIT
    fun validationProblem(): String? = when {
        !leash.isFinite() || leash !in 1.0..32.0 -> "reaction leash must be in [1, 32]"
        durationTicks !in 20..2400 -> "reaction duration must be 20..2400 ticks"
        cooldownTicks !in 20..200 -> "reaction cooldown must be 20..200 ticks"
        protectsSubject != (subjectUuid != null) -> "protection requires exactly one subject; other reactions cannot contain it"
        (protectsSubject || mode == TaskReactionMode.AREA) != (anchor != null) -> "protection and area reactions require a fixed anchor"
        (mode == TaskReactionMode.AREA) != !filter.isEmpty -> "only area reactions require an explicit type/tag filter"
        anchor != null && (!anchor.x.isFinite() || !anchor.y.isFinite() || !anchor.z.isFinite() ||
            abs(anchor.x) > 29_999_984 || abs(anchor.z) > 29_999_984 || abs(anchor.y) > 2048) -> "reaction anchor is outside supported coordinates"
        else -> tactics.validationProblem()
    }
}

/** A persisted accepted-hit watermark, not an entity reference or a transient Core action ID. */
internal data class TaskProtectionHit(val subject: UUID, val attacker: UUID, val gameTime: Long)

internal class TaskReactionState(
    var policy: TaskReactionPolicy = TaskReactionPolicy(),
    var cooldownRemaining: Int = 0,
    var consumedProtectionHit: TaskProtectionHit? = null,
    var activeFrame: UUID? = null,
) {
    /** Core's own damage identity is transient; loading the body cannot replay a pre-load hit. */
    var handledDamage: UUID? = null
}
