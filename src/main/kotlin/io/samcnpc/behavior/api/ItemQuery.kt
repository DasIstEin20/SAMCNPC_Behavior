package io.samcnpc.behavior.api

import io.samcnpc.core.api.*

/** Bounded semantic selection over authoritative item facts, never item-name heuristics. */
sealed interface ItemQuery {
    fun matches(stack: NpcItemStackSnapshot, knowledge: NpcItemKnowledge): Boolean
    fun encode(): String

    data class Exact(val itemId: String) : ItemQuery {
        init { require(validItemId(itemId)) { "invalid exact item ID" } }
        override fun matches(stack: NpcItemStackSnapshot, knowledge: NpcItemKnowledge) = stack.count > 0 && stack.itemId == itemId
        override fun encode() = itemId
    }
    data class Role(val role: ItemQueryRole) : ItemQuery {
        override fun matches(stack: NpcItemStackSnapshot, knowledge: NpcItemKnowledge): Boolean {
            if (stack.isEmpty || stack.count <= 0 || knowledge.itemId != stack.itemId) return false
            return when (role) {
                ItemQueryRole.AXE -> knowledge.toolKind == NpcToolKind.AXE
                ItemQueryRole.PICKAXE -> knowledge.toolKind == NpcToolKind.PICKAXE
                ItemQueryRole.SHOVEL -> knowledge.toolKind == NpcToolKind.SHOVEL
                ItemQueryRole.HOE -> knowledge.toolKind == NpcToolKind.HOE
                ItemQueryRole.FOOD -> knowledge.edible
                ItemQueryRole.PLACEABLE_BLOCK -> knowledge.placeableBlock != null
                ItemQueryRole.TOOL -> knowledge.isTool
                ItemQueryRole.SHIELD -> NpcItemRole.SHIELD in knowledge.roles
                ItemQueryRole.ARMOR -> NpcItemRole.ARMOR in knowledge.roles
                ItemQueryRole.MELEE_WEAPON -> NpcItemRole.MELEE_WEAPON in knowledge.roles
                ItemQueryRole.RANGED_WEAPON -> NpcItemRole.RANGED_WEAPON in knowledge.roles
                ItemQueryRole.AMMUNITION -> NpcItemRole.AMMUNITION in knowledge.roles
            }
        }
        override fun encode() = "@" + role.name.lowercase(java.util.Locale.ROOT)
    }
    class AnyOf(alternatives: List<ItemQuery>) : ItemQuery {
        val alternatives: List<ItemQuery> = java.util.List.copyOf(alternatives)
        init {
            require(alternatives.size in 2..8 && alternatives.none { it is AnyOf } && alternatives.map { it.encode() }.distinct().size == alternatives.size)
            require(encode().length <= MAX_TEXT_LENGTH)
        }
        override fun matches(stack: NpcItemStackSnapshot, knowledge: NpcItemKnowledge) = alternatives.any { it.matches(stack, knowledge) }
        override fun encode() = alternatives.joinToString("|") { it.encode() }
        override fun equals(other: Any?) = other is AnyOf && alternatives == other.alternatives
        override fun hashCode() = alternatives.hashCode()
    }

    companion object {
        const val MAX_TEXT_LENGTH = 512
        const val PATTERN = "^(?:(?:[a-z0-9_.-]+:[a-z0-9_./-]+|@(axe|pickaxe|shovel|hoe|food|placeable_block|tool|shield|armor|(?:melee|ranged)_weapon|ammunition))(?:[|](?!$)|$)){1,8}$"
        private val itemIdPattern = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        fun validItemId(id: String) = id.length in 3..128 && itemIdPattern.matches(id)
        /** Cold boundary only: definitions and persisted documents compile this once. */
        fun parse(text: String): ItemQuery {
            require(text.length in 1..MAX_TEXT_LENGTH) { "item query must contain 1..512 characters" }
            val parts = text.split('|')
            require(parts.size in 1..8 && parts.distinct().size == parts.size) { "item query needs 1..8 distinct alternatives" }
            val values = parts.map { part ->
                if (part.startsWith('@')) Role(ItemQueryRole.entries.firstOrNull { "@" + it.name.lowercase(java.util.Locale.ROOT) == part }
                    ?: throw IllegalArgumentException("unknown authoritative item role: $part"))
                else Exact(part)
            }
            return if (values.size == 1) values.single() else AnyOf(values)
        }
        fun durability(stack: NpcItemStackSnapshot): Double = when {
            stack.isEmpty || stack.count <= 0 -> 0.0
            stack.maxDamage <= 0 -> 1.0
            else -> (stack.maxDamage - stack.damage).coerceAtLeast(0).toDouble() / stack.maxDamage
        }
    }
}

enum class ItemQueryRole { AXE, PICKAXE, SHOVEL, HOE, FOOD, PLACEABLE_BLOCK, TOOL, SHIELD, ARMOR, MELEE_WEAPON, RANGED_WEAPON, AMMUNITION }
