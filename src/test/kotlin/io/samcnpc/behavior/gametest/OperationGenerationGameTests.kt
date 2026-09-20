package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionStatus
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.players.ServerOpListEntry
import net.minecraft.world.entity.Entity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object OperationGenerationGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 160, batch = "operation_generations")
    fun reloadUnloadedBodyAndInPlaceNbtLoadInvalidateTheAppropriateGeneration(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level, "GenerationSam")
        val arena = CombatGameTestArena(helper, actor.player)
        val server = helper.level.server
        var restored: Entity? = null
        var grantedOperator = false
        arena.onReady { npc ->
            val npcUuid = npc.npcUuid
            try {
                check(npc.inventoryLoadSnapshot() == null)
                fun capture(): OperationInspection {
                    val reply = OperationInspectionApi.inspect(server, actor.player, npcUuid)
                    check(reply.result.status == NpcActionStatus.SUCCEEDED) { reply.result.detail }
                    return checkNotNull(reply.inspection)
                }
                val first = capture()
                check(first.generations == capture().generations)
                val report = BehaviorRuntimeService.reload()
                val reloadedRegistry = capture()
                check(first.generations.serverSession == reloadedRegistry.generations.serverSession)
                check(first.generations.body == reloadedRegistry.generations.body)
                check(first.generations.registry != reloadedRegistry.generations.registry) { "Registry did not activate: $report" }
                val saved = arena.body.saveWithoutId(CompoundTag())
                val type = arena.body.type
                val handle = checkNotNull(CoreNpcApi.service(server).find(npcUuid))
                arena.body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
                check(CoreNpcApi.service(server).runtime(handle) == null)
                val body = checkNotNull(type.create(helper.level))
                restored = body
                // A fresh body with the same UUID has no load marker, so unload invalidation itself must work.
                body.setUUID(npcUuid)
                body.moveTo(arena.start.x, arena.start.y, arena.start.z, 0F, 0F)
                if (!actor.player.hasPermissions(2)) {
                    // GameTestServer defaults op() to level zero; this fixture explicitly exercises level two.
                    server.playerList.ops.add(ServerOpListEntry(actor.player.gameProfile, 2, false))
                    grantedOperator = true
                }
                check(actor.player.hasPermissions(2))
                check(helper.level.addFreshEntity(body))
                check(checkNotNull(CoreNpcApi.service(server).runtime(handle)).inventoryLoadSnapshot() == null)
                val afterLoad = capture()
                check(afterLoad.generations.serverSession == reloadedRegistry.generations.serverSession)
                check(afterLoad.generations.registry == reloadedRegistry.generations.registry)
                check(afterLoad.generations.body != reloadedRegistry.generations.body)
                body.load(saved)
                val inPlace = capture()
                check(inPlace.generations.body != afterLoad.generations.body)
                check(inPlace.generations.registry == afterLoad.generations.registry)
                check(first.physical.npcUuid == inPlace.physical.npcUuid)
                check(first.generations.body != inPlace.generations.body)
            } finally {
                restored?.discard()
                arena.close()
                if (grantedOperator) server.playerList.deop(actor.player.gameProfile)
                actor.close()
            }
            helper.succeed()
        }
    }
}
