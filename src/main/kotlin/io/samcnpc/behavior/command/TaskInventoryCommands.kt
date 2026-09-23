package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

internal object TaskInventoryCommands {
    fun supply() = branch("supply", false, InventoryWorkKind.SUPPLY)
    fun unload() = branch("unload", false, InventoryWorkKind.UNLOAD)
    fun pickup() = branch("pickup", false, InventoryWorkKind.PICKUP)
    fun policy() = Commands.literal("logistics").then(Commands.argument("npc", StringArgumentType.word())
        .then(branch("supply", true, InventoryWorkKind.SUPPLY))
        .then(branch("unload", true, InventoryWorkKind.UNLOAD))
        .then(branch("pickup", true, InventoryWorkKind.PICKUP))
        .then(Commands.literal("off").executes { context -> TaskCommands.withNpc(context) { player, npc ->
            TaskAmendments.automatic(player.server, npc, player, TaskChange.Logistics(TaskLogisticsPolicy()))
        } })
        .then(Commands.literal("limits").then(Commands.argument("travelRadius", DoubleArgumentType.doubleArg(4.0, 64.0))
            .then(Commands.argument("workTicks", IntegerArgumentType.integer(20, 36000))
                .then(Commands.argument("durationTicks", IntegerArgumentType.integer(40, 72000))
                    .then(Commands.argument("cooldownTicks", IntegerArgumentType.integer(20, 6000)).executes { context -> TaskCommands.withNpc(context) { player, npc ->
                        val record = TaskStore.forServer(player.server).get(npc.npcUuid) ?: return@withNpc NpcActionResult.rejected("no assigned task")
                        val old = record.logistics.policy
                        if (!old.enabled) return@withNpc NpcActionResult.rejected("enable an inventory policy before changing its limits")
                        TaskAmendments.automatic(player.server, npc, player, TaskChange.Logistics(old.copy(
                            travelRadius = DoubleArgumentType.getDouble(context, "travelRadius"), workTicks = integer(context, "workTicks"),
                            durationTicks = integer(context, "durationTicks"), cooldownTicks = integer(context, "cooldownTicks"))))
                    } }))))))
    private fun branch(name: String, policy: Boolean, kind: InventoryWorkKind): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal(name)
        if (kind == InventoryWorkKind.PICKUP) {
            root.then(Commands.argument("items", StringArgumentType.string()).then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0, 8.0))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 256)).executes { execute(it, policy, kind) })))
        } else root.then(Commands.argument("containers", StringArgumentType.string()).then(Commands.argument("items", StringArgumentType.string())
            .executes { execute(it, policy, kind) }))
        return root
    }
    private fun execute(context: CommandContext<CommandSourceStack>, policy: Boolean, kind: InventoryWorkKind): Int = TaskCommands.withNpc(context) { player, npc ->
        try {
            val items = StringArgumentType.getString(context, "items")
            val work = when (kind) {
                InventoryWorkKind.SUPPLY -> SupplyStock(parseNeeds(items), ContainerChoices(TaskTransportCommands.parsePositions(StringArgumentType.getString(context, "containers"))))
                InventoryWorkKind.UNLOAD -> UnloadExcess(parseReserves(items), ContainerChoices(TaskTransportCommands.parsePositions(StringArgumentType.getString(context, "containers"))))
                InventoryWorkKind.PICKUP -> PickupNearby(parts(items), DoubleArgumentType.getDouble(context, "radius"), integer(context, "count"))
                // This legacy grammar requires an explicit item list; collection uses the versioned operation API.
                InventoryWorkKind.COLLECT -> return@withNpc NpcActionResult.rejected("COLLECT requires an operation document with one source")
            }
            if (policy) {
                val record = TaskStore.forServer(player.server).get(npc.npcUuid) ?: return@withNpc NpcActionResult.rejected("no assigned task")
                val old = record.logistics.policy
                val next = old.copy(anchor = old.anchor ?: npc.snapshot().position,
                    supply = if (work is SupplyStock) work else old.supply,
                    unload = if (work is UnloadExcess) work else old.unload,
                    pickup = if (work is PickupNearby) work else old.pickup)
                TaskAmendments.automatic(player.server, npc, player, TaskChange.Logistics(next))
            } else {
                val snapshot = npc.snapshot()
                TaskService.assign(player.server, npc, InventoryTaskDefinition(snapshot.dimensionId, work, snapshot.position))
            }
        } catch (error: IllegalArgumentException) { NpcActionResult.rejected(error.message ?: "invalid bounded inventory request") }
    }
    internal fun parseNeeds(value: String): List<StockNeed> = parts(value).map { part ->
        val pair = part.split('='); require(pair.size == 2) { "supply items use item=minimum/target[/sourceReserve]" }
        val numbers = pair[1].split('/').map { it.toIntOrNull() ?: throw IllegalArgumentException("supply quantities must be integers") }
        require(numbers.size in 2..3) { "supply needs minimum/target[/sourceReserve]" }
        StockNeed(pair[0], numbers[0], numbers[1], numbers.getOrElse(2) { 0 })
    }
    internal fun parseReserves(value: String): List<ItemReserve> = parts(value).map { part ->
        val pair = part.split('='); require(pair.size == 2) { "unload items use item=reserve" }
        ItemReserve(pair[0], pair[1].toIntOrNull() ?: throw IllegalArgumentException("reserve must be an integer"))
    }
    private fun parts(value: String): List<String> {
        require(value.length in 1..4096) { "inventory item list is empty or oversized" }
        val parts = value.split(';'); require(parts.size in 1..16 && parts.none { it.isBlank() }) { "inventory item list needs 1..16 entries" }; return parts
    }
    private fun integer(context: CommandContext<CommandSourceStack>, key: String) = IntegerArgumentType.getInteger(context, key)
}
