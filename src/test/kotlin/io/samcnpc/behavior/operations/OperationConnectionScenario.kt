package io.samcnpc.behavior.operations

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.TaskNavigator
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/** Native PlayerList leave/rejoin is tested separately from authenticated skin/account evidence. */
internal class OperationConnectionScenario(private val server: MinecraftServer) {
    private val origin = BlockPos(1500, 80, 1000)
    private val playerId = UUID.randomUUID()
    private var actor: OperationActor? = null
    private var stage = 0
    private var age = 0
    private var ticks = 0
    private var idlePosition: NpcPosition? = null
    private var route: UUID? = null
    val npcId: UUID
    var complete = false
        private set

    init {
        val level = server.overworld()
        for (x in -3..30) for (z in -3..3) for (y in 0..4)
            level.setBlock(origin.offset(x, y, z), (if (y == 0) Blocks.STONE else Blocks.AIR).defaultBlockState(), 3)
        val joined = OperationActor(level, playerId)
        actor = joined
        movePlayer(joined)
        val result = CoreNpcApi.service(server).summon(NpcSummonRequest(playerId, "O4Connection",
            level.dimension().location().toString(), NpcPosition(origin.x + 0.5, origin.y + 1.0, origin.z + 0.5), -90.0F))
        check(result.result.status == NpcActionStatus.SUCCEEDED)
        npcId = checkNotNull(result.handle).npcUuid
    }

    fun tick() {
        if (complete) return
        check(++ticks < 800) { "Summoner leave/rejoin timed out stage=$stage" }
        val service = CoreNpcApi.service(server)
        val npc = service.find(npcId)?.let(service::runtime) ?: return
        val snapshot = npc.snapshot()
        if (!snapshot.onGround) return
        age++
        check(snapshot.summonerUuid == playerId)
        when (stage) {
            0 -> {
                check(BehaviorRuntimeService.assignPacks(server, npcId, listOf("samcnpc:follow_summoner")).status == NpcActionStatus.SUCCEEDED)
                advance()
            }
            1 -> {
                val navigation = snapshot.navigation ?: return
                if (snapshot.position.x < origin.x + 1.5) return
                route = navigation.actionId
                checkNotNull(actor).close()
                actor = null
                check(server.playerList.getPlayer(playerId) == null)
                advance()
            }
            2 -> {
                check(snapshot.navigation == null && snapshot.control == null)
                check(BehaviorRuntimeService.assignedPacks(server, npcId) == listOf("samcnpc:follow_summoner"))
                if (age == 5) idlePosition = snapshot.position
                val idle = idlePosition
                if (idle != null) check(TaskNavigator.distanceSquared(idle, snapshot.position) <= 0.0025)
                if (age < 40) return
                val rejoined = OperationActor(server.overworld(), playerId)
                actor = rejoined
                movePlayer(rejoined)
                check(server.playerList.getPlayer(playerId)?.uuid == playerId)
                advance()
            }
            3 -> {
                val navigation = snapshot.navigation ?: return
                check(navigation.actionId != route)
                advance()
            }
            4 -> {
                val joined = checkNotNull(actor)
                val goal = NpcPosition(joined.player.x, joined.player.y, joined.player.z)
                if (TaskNavigator.distanceSquared(snapshot.position, goal) > 3.2 * 3.2 ||
                    snapshot.navigation != null || snapshot.control != null) return
                check(BehaviorRuntimeService.assignPacks(server, npcId, emptyList()).status == NpcActionStatus.SUCCEEDED)
                check(service.dismiss(checkNotNull(service.find(npcId)), NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
                joined.close()
                actor = null
                check(service.find(npcId)?.let(service::runtime) == null && BehaviorRuntimeService.diagnostic(npcId) == null)
                complete = true
            }
        }
    }

    private fun advance() { stage++; age = 0 }
    private fun movePlayer(joined: OperationActor) = joined.player.teleportTo(server.overworld(),
        origin.x + 24.5, origin.y + 1.0, origin.z + 0.5, 90.0F, 0.0F)
}
