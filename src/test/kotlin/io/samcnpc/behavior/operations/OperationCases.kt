package io.samcnpc.behavior.operations

import net.minecraft.server.MinecraftServer
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules

internal object OperationCases {
    private val combat=setOf(OperationKind.ATTACK,OperationKind.DEFEND,OperationKind.AREA_ATTACK,OperationKind.PATROL)
    private val resources=setOf(OperationKind.MINING,OperationKind.AMEND_RESOURCE,OperationKind.FARM,OperationKind.PLANTING,OperationKind.WOOD,OperationKind.WOOD_REPLANT)
    fun prepare(s: OperationScene) = when(s.kind) {
        in combat -> OperationCombatCases.prepare(s)
        in resources -> OperationResourceCases.prepare(s)
        else -> OperationCargoCases.prepare(s)
    }
    fun checkpoint(s: OperationScene) = when(s.kind) {
        in combat -> OperationCombatCases.checkpoint(s)
        in resources -> OperationResourceCases.checkpoint(s)
        else -> OperationCargoCases.checkpoint(s)
    }
    fun verify(s: OperationScene) = when(s.kind) {
        in combat -> OperationCombatCases.verify(s)
        in resources -> OperationResourceCases.verify(s)
        else -> OperationCargoCases.verify(s)
    }
    fun configure(server: MinecraftServer) {
        val expected = System.getProperty("samcnpc.verificationModIds", "samcnpc_core,samcnpc_behavior").split(',').toSet()
        val loaded = net.minecraftforge.fml.ModList.get().mods.map { it.modId }.filter { it.startsWith("samcnpc_") }.toSortedSet()
        check(loaded == expected) { "Operation mod set differs: expected=$expected loaded=$loaded" }
        com.mojang.logging.LogUtils.getLogger().info("OPERATIONS_MODS expected={} loaded={}", expected, loaded)
        server.setDifficulty(Difficulty.NORMAL,true)
        val level=server.overworld()
        level.dayTime=6000
        level.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false,server)
        level.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server)
        level.gameRules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server)
        level.gameRules.getRule(GameRules.RULE_RANDOMTICKING).set(0,server)
        level.setWeatherParameters(6000,0,false,false)
    }
}
