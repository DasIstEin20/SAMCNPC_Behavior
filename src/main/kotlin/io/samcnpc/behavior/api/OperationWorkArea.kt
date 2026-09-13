package io.samcnpc.behavior.api

import io.samcnpc.core.api.NpcBlockPosition

data class OperationWorkBox(val min: NpcBlockPosition, val max: NpcBlockPosition)

class OperationWorkArea(val bounds: OperationWorkBox, exclusions: List<OperationWorkBox> = emptyList()) {
    init { require(exclusions.size <= 16) { "work permits at most sixteen exclusions" } }
    val exclusions: List<OperationWorkBox> = java.util.List.copyOf(exclusions)
}

/** Literal namespaced resources; unsupported world resources are rechecked during execution. */
class OperationResourceIds(ids: List<String>) {
    init {
        require(ids.size in 1..64 && ids.distinct().size == ids.size) { "resources require one to sixty-four distinct IDs" }
        require(ids.all { it.length <= 256 && ID.matches(it) }) { "resources require bounded namespaced IDs" }
    }
    val values: List<String> = java.util.List.copyOf(ids)
    companion object { private val ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$") }
}

class OperationWoodSelection(selectors: List<String>) {
    init { require(selectors.size in 1..16) { "wood requires one to sixteen selectors" } }
    val selectors: List<String> = java.util.List.copyOf(selectors)
}
