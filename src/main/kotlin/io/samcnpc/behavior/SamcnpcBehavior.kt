package io.samcnpc.behavior

import io.samcnpc.behavior.command.BehaviorCommands
import io.samcnpc.behavior.lumberjack.LumberjackService
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod

@Mod(SamcnpcBehavior.MOD_ID)
class SamcnpcBehavior {
    init {
        RuntimeCompatibility.verify()
        BehaviorRuntimeService.reloadAtStartup()
        MinecraftForge.EVENT_BUS.register(BehaviorRuntimeService)
        MinecraftForge.EVENT_BUS.register(BehaviorCommands)
        MinecraftForge.EVENT_BUS.register(LumberjackService)
        MinecraftForge.EVENT_BUS.register(io.samcnpc.behavior.kernel.work.HarvestWorkClaims)
        MinecraftForge.EVENT_BUS.register(io.samcnpc.behavior.task.FoodAccounting)
        MinecraftForge.EVENT_BUS.register(io.samcnpc.behavior.task.FarmAccounting)
        MinecraftForge.EVENT_BUS.register(io.samcnpc.behavior.task.InventoryPickupAccounting)
    }

    companion object {
        const val MOD_ID: String = "samcnpc_behavior"
    }
}
