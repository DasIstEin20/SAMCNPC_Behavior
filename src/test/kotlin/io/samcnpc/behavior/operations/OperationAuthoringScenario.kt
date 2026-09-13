package io.samcnpc.behavior.operations

import com.google.gson.JsonParser
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.TaskNavigator
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.sqrt

/** Runs the documented author example through disk reload and actual native movement. */
internal class OperationAuthoringScenario(private val server: MinecraftServer) {
    private val origin = BlockPos(900, 80, 1000)
    private val actor = OperationActor(server.overworld(), UUID.randomUUID())
    private val file = BehaviorRuntimeService.externalDirectory().resolve("author_follow.json")
    private val originalIds = BehaviorRuntimeService.activePackIds()
    private val example: String
    private var stage = 0
    private var ticks = 0
    private var settledTicks = 0
    private var heldTicks = 0
    private var heldAt: NpcPosition? = null
    private var farDistance = 0.0
    private var nearDistance = 0.0
    private var resumed = false
    val npcId: UUID
    var complete = false
        private set
    val report: String get() = "author edit/reload: same_npc=true example=example:follow stopDistance=8->2 " +
        "far=$farDistance near=$nearDistance hold_ticks=$heldTicks resumed=$resumed physical_movement=true"

    init {
        check(!Files.exists(file) && "example:follow" !in originalIds) { "Use a fresh operationsSmokeId" }
        val local = Path.of("../docs/examples/behavior-packs/follow.json")
        val path = if (Files.isRegularFile(local)) local else Path.of("../../docs/examples/behavior-packs/follow.json")
        example = Files.readString(path)
        val level = server.overworld()
        for (x in -3..30) for (z in -3..3) for (y in 0..4)
            level.setBlock(origin.offset(x, y, z), (if (y == 0) Blocks.STONE else Blocks.AIR).defaultBlockState(), 3)
        actor.player.teleportTo(level, origin.x + 24.5, origin.y + 1.0, origin.z + 0.5, 90.0F, 0.0F)
        val result = CoreNpcApi.service(server).summon(NpcSummonRequest(actor.player.uuid, "AuthorReload",
            level.dimension().location().toString(), NpcPosition(origin.x + 0.5, origin.y + 1.0, origin.z + 0.5), -90.0F))
        check(result.result.status == NpcActionStatus.SUCCEEDED)
        npcId = checkNotNull(result.handle).npcUuid
    }

    fun tick() {
        if (complete) return
        check(++ticks < 700) { "Author example timed out stage=$stage" }
        val service = CoreNpcApi.service(server)
        val npc = service.find(npcId)?.let(service::runtime) ?: return
        val snapshot = npc.snapshot()
        if (!snapshot.onGround) return
        check(snapshot.summonerUuid == actor.player.uuid)
        val target = NpcPosition(actor.player.x, actor.player.y, actor.player.z)
        val distance = sqrt(TaskNavigator.distanceSquared(snapshot.position, target))
        when (stage) {
            0 -> {
                val document = JsonParser.parseString(example).asJsonObject
                check(document.get("id").asString == "example:follow")
                document.getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("actions")[0]
                    .asJsonObject.getAsJsonObject("args").addProperty("stopDistance", 8)
                Files.createDirectories(file.parent)
                Files.writeString(file, document.toString())
                check(BehaviorRuntimeService.reload().accepted)
                check(BehaviorRuntimeService.assignPacks(server, npcId, listOf("example:follow")).status == NpcActionStatus.SUCCEEDED)
                stage = 1
            }
            1 -> {
                if (snapshot.navigation != null || snapshot.control != null || distance > 8.1) return
                check(distance >= 6.5)
                if (++settledTicks < 5) return
                farDistance = distance
                heldAt = snapshot.position
                stage = 2
            }
            2 -> {
                check(snapshot.navigation == null && snapshot.control == null)
                check(TaskNavigator.distanceSquared(checkNotNull(heldAt), snapshot.position) <= 0.0025)
                check(checkNotNull(BehaviorRuntimeService.diagnostic(npcId)).selectedIntents.any {
                    it.contains("example:follow") && it.contains("samcnpc:move_to_summoner")
                })
                if (++heldTicks < 20) return
                Files.writeString(file, example)
                check(BehaviorRuntimeService.reload().accepted)
                check(BehaviorRuntimeService.assignedPacks(server, npcId) == listOf("example:follow"))
                stage = 3
            }
            3 -> {
                if (snapshot.navigation == null) return
                resumed = true
                stage = 4
            }
            4 -> {
                if (snapshot.navigation != null || snapshot.control != null || distance > 2.2) return
                nearDistance = distance
                check(distance >= 1.0 && farDistance - nearDistance >= 4.5)
                check(TaskNavigator.distanceSquared(checkNotNull(heldAt), snapshot.position) >= 4.5 * 4.5)
                check(BehaviorRuntimeService.assignPacks(server, npcId, emptyList()).status == NpcActionStatus.SUCCEEDED)
                Files.delete(file)
                check(BehaviorRuntimeService.reload().accepted && BehaviorRuntimeService.activePackIds() == originalIds)
                check(service.dismiss(checkNotNull(service.find(npcId)), NpcDismissMode.ONLY_IF_EMPTY).status == NpcActionStatus.SUCCEEDED)
                actor.close()
                complete = true
            }
        }
    }
}
