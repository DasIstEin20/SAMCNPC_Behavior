package io.samcnpc.behavior.registry

import com.google.gson.JsonObject
import io.samcnpc.behavior.model.ActionHandler
import io.samcnpc.behavior.model.BehaviorChannel
import io.samcnpc.behavior.model.ConditionHandler

/** JSON exists only at the loading boundary. Compilers capture validated concrete values. */
class ConditionDefinition(
    val id: String,
    val validateArgs: (JsonObject) -> String?,
    val compile: (JsonObject) -> ConditionHandler,
)

class ActionDefinition(
    val id: String,
    channels: Set<BehaviorChannel>,
    val validateArgs: (JsonObject) -> String?,
    val compile: (JsonObject) -> ActionHandler,
) {
    val channels: Set<BehaviorChannel> = java.util.Set.copyOf(channels)
}
