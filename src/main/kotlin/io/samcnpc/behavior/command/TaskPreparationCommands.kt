package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.*
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.api.ItemQuery
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

/** Manual authoring front end; the same task/policy validator owns every bound and permission. */
internal object TaskPreparationCommands {
    fun ensure(policy: Boolean) = Commands.literal(if (policy) "prepare" else "ensure")
        .then(Commands.argument("query",StringArgumentType.string())
            .then(Commands.argument("count",IntegerArgumentType.integer(1,64))
                .then(Commands.argument("containers",StringArgumentType.string())
                    .then(Commands.argument("destination",StringArgumentType.word())
                        .then(Commands.argument("minimumDurability",DoubleArgumentType.doubleArg(0.0,1.0))
                            .then(Commands.argument("sourceReserve",IntegerArgumentType.integer(0,2304)).executes { context ->
                                execute(context,policy) {
                                    val source=StringArgumentType.getString(context,"containers")
                                    val destination=StringArgumentType.getString(context,"destination")
                                    EnsureItems(ItemQuery.parse(StringArgumentType.getString(context,"query")),IntegerArgumentType.getInteger(context,"count"),
                                        if(source=="-") null else ContainerChoices(TaskTransportCommands.parsePositions(source)),
                                        DoubleArgumentType.getDouble(context,"minimumDurability"),
                                        if(destination=="NONE") null else NpcEquipmentDestination.valueOf(destination),IntegerArgumentType.getInteger(context,"sourceReserve"))
                                }
                            }))))))
    fun freeSlots(policy: Boolean) = Commands.literal("free_slots")
        .then(Commands.argument("containers",StringArgumentType.string())
            .then(Commands.argument("reserves",StringArgumentType.string())
                .then(Commands.argument("slots",IntegerArgumentType.integer(1,36)).executes { context ->
                    execute(context,policy) { UnloadExcess(TaskInventoryCommands.parseReserves(StringArgumentType.getString(context,"reserves")),
                        ContainerChoices(TaskTransportCommands.parsePositions(StringArgumentType.getString(context,"containers"))),IntegerArgumentType.getInteger(context,"slots")) }
                })))
    private fun execute(context: CommandContext<CommandSourceStack>,policy: Boolean,work: () -> InventoryWork): Int =
        TaskCommands.withNpc(context) { player,npc ->
            try {
                val selected=work();val snapshot=npc.snapshot()
                if(policy) {
                    val record=TaskStore.forServer(player.server).get(npc.npcUuid) ?: return@withNpc NpcActionResult.rejected("no assigned task")
                    val old=record.logistics.policy
                    TaskAmendments.automatic(player.server,npc,player,TaskChange.Logistics(old.copy(anchor=old.anchor ?: snapshot.position,
                        preparation=if(selected is EnsureItems) selected else old.preparation,unload=if(selected is UnloadExcess) selected else old.unload)))
                } else TaskService.assign(player.server,npc,InventoryTaskDefinition(snapshot.dimensionId,selected,snapshot.position,version=3))
            } catch(error: IllegalArgumentException) { NpcActionResult.rejected(error.message ?: "invalid bounded preparation request") }
        }
}
