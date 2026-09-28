package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.StringArgumentType
import io.samcnpc.behavior.api.MissionApi
import io.samcnpc.behavior.api.MissionControl
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

internal object MissionCommands {
    fun branch() = Commands.literal("mission")
        .then(Commands.literal("list").executes { context -> context.source.sendSuccess({ Component.literal(MissionApi.availableIds().joinToString(", ")) },false); 1 })
        .then(Commands.literal("start").then(Commands.argument("npc",StringArgumentType.word())
            .then(Commands.argument("mission",StringArgumentType.word()).executes { context ->
                TaskCommands.withNpc(context) { player,npc -> MissionApi.start(player.server,player,npc.npcUuid,StringArgumentType.getString(context,"mission")) }
            })))
        .also { root -> for (control in MissionControl.entries) root.then(Commands.literal(control.name.lowercase(java.util.Locale.ROOT))
            .then(Commands.argument("npc",StringArgumentType.word()).executes { context ->
                TaskCommands.withNpc(context) { player,npc -> MissionApi.control(player.server,player,npc.npcUuid,control) }
            })) }
}
