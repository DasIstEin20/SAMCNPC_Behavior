package io.samcnpc.behavior.api

import io.samcnpc.behavior.registry.RegisteredBehaviorSchema

/** Cached schema for tooling. Runtime validation remains BehaviorPackValidationApi. */
object BehaviorSchemaApi {
    fun registeredSchema(): String = RegisteredBehaviorSchema.json
}
