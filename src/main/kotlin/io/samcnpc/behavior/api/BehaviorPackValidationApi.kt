package io.samcnpc.behavior.api

import io.samcnpc.behavior.registry.BehaviorDefinitions

/** Public validation gateway used by the optional LLM boundary and disk reloads alike. */
object BehaviorPackValidationApi {
    fun validateCandidate(json: String, source: String = "proposal"): ValidationReport =
        BehaviorDefinitions.compiler.compile(source, json).report
}

data class ValidationReport(
    val accepted: Boolean,
    val messages: List<String>,
)
