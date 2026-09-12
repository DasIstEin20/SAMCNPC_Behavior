package io.samcnpc.behavior.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument

internal object TaskFishingCommands {
    fun branch() = Commands.literal("fish")
        .then(Commands.argument("water",BlockPosArgument.blockPos())
            .then(Commands.argument("catches",IntegerArgumentType.integer(1,64))
                .executes { execute(it,false,6000,240) }
                .then(Commands.argument("standing",Vec3Argument.vec3())
                    .executes { execute(it,true,6000,240) }
                    .then(Commands.argument("durationTicks",IntegerArgumentType.integer(40,72000))
                        .then(Commands.argument("pickupWaitTicks",IntegerArgumentType.integer(40,1200))
                            .executes { execute(it,true,IntegerArgumentType.getInteger(it,"durationTicks"),IntegerArgumentType.getInteger(it,"pickupWaitTicks")) })))))
    private fun execute(context: CommandContext<CommandSourceStack>,explicitStanding: Boolean,duration: Int,pickupWait: Int): Int = TaskCommands.withNpc(context) { player,npc ->
        val snapshot=npc.snapshot()
        val water=BlockPosArgument.getBlockPos(context,"water")
        val standing=if (explicitStanding) {
            val pos=Vec3Argument.getVec3(context,"standing");NpcPosition(pos.x,pos.y,pos.z)
        } else snapshot.position
        TaskService.assign(player.server,npc,FishingTaskDefinition(snapshot.dimensionId,NpcBlockPosition(water.x,water.y,water.z),standing,
            IntegerArgumentType.getInteger(context,"catches"),snapshot.position,pickupWaitTicks=pickupWait,budget=TaskBudget(duration)))
    }
}
