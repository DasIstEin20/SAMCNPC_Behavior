package io.samcnpc.behavior.runtime

import com.google.gson.JsonObject
import io.samcnpc.behavior.model.BehaviorReadContext
import io.samcnpc.behavior.model.CompiledAction
import io.samcnpc.behavior.registry.BehaviorDefinitions
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcSnapshot
import io.samcnpc.core.api.NpcVector
import java.util.UUID

internal fun decisionContext(tick: Long = 100, health: Double = 0.6): BehaviorReadContext =
    BehaviorReadContext(NpcSnapshot(
        npcUuid = UUID(0, 1), summonerUuid = UUID(0, 2), dimensionId = "minecraft:overworld",
        position = NpcPosition(0.0, 64.0, 0.0), eyePosition = NpcPosition(0.0, 65.62, 0.0),
        velocity = NpcVector(0.0, 0.0, 0.0), yaw = 0.0F, pitch = 0.0F, onGround = true,
        inWater = false, climbing = false, sprinting = false, sneaking = false,
        lastDamageSourceEntityUuid = null, lastDamageAgeTicks = null, healthFraction = health,
        gameTime = tick, attackStrength = 1.0F, itemUse = null, blockBreak = null,
    ), null, null)

internal fun registeredAction(id: String, args: JsonObject = JsonObject()): CompiledAction {
    val definition = BehaviorDefinitions.actions.getValue(id)
    check(definition.validateArgs(args) == null)
    return CompiledAction(id, definition.compile(args), definition.channels)
}
