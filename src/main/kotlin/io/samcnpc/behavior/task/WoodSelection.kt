package io.samcnpc.behavior.task

/** Versioned built-in selections, expanded once; ticks use only immutable ID membership. */
internal class WoodSelection(selectors: Collection<String>) {
    val selectors: Set<String> = java.util.Set.copyOf(selectors)
    private val itemIds: Set<String> = java.util.Set.copyOf(selectors.flatMap { PRESETS[it] ?: listOf(it) })
    fun matches(itemId: String): Boolean = itemId in itemIds
    fun validationProblem(): String? = when {
        selectors.isEmpty() || selectors.size > 16 -> "choose 1..16 registered wood selections"
        selectors.any { it !in PRESETS && it !in SUPPORTED_ITEMS } -> "unknown wood selection; supported presets are samcnpc:oak, samcnpc:birch, samcnpc:dark_oak and samcnpc:oak_and_birch"
        else -> null
    }
    override fun equals(other: Any?): Boolean = other is WoodSelection && selectors == other.selectors
    override fun hashCode(): Int = selectors.hashCode()
    override fun toString(): String = selectors.sorted().joinToString(prefix = "WoodSelection[", postfix = "]")
    companion object {
        private val OAK = setOf("minecraft:oak_log", "minecraft:oak_wood", "minecraft:stripped_oak_log", "minecraft:stripped_oak_wood")
        private val BIRCH = setOf("minecraft:birch_log", "minecraft:birch_wood", "minecraft:stripped_birch_log", "minecraft:stripped_birch_wood")
        private val DARK_OAK = setOf("minecraft:dark_oak_log", "minecraft:dark_oak_wood", "minecraft:stripped_dark_oak_log", "minecraft:stripped_dark_oak_wood")
        private val SUPPORTED_ITEMS = OAK + BIRCH + DARK_OAK
        private val PRESETS = mapOf("samcnpc:oak" to OAK, "samcnpc:birch" to BIRCH,
            "samcnpc:dark_oak" to DARK_OAK, "samcnpc:oak_and_birch" to OAK + BIRCH)
    }
}

internal enum class WorkToolPolicy { INHERIT_CORE_SETTINGS }
