package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.TaskNavigator
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/** Same world assertions for a controlled GameTest player and a real connected client player. */
internal class FollowWorkScenario(val level: ServerLevel, val player: ServerPlayer, private val origin: BlockPos) {
    private val service = CoreNpcApi.service(level.server)
    val npcUuid: UUID
    var outcome: String? = null
        private set
    var phase = 0
        private set
    private var age = 0
    private var totalTicks = 0
    private var sawRoute = false
    private var sawDetour = false
    private var settled: NpcPosition? = null
    private var previousAction: UUID? = null
    private var initial: NpcPosition? = null

    init {
        for (x in -4..26) for (z in -9..9) for (y in 0..10) {
            level.setBlock(origin.offset(x, y, z), (if (y == 0) Blocks.GRASS_BLOCK else Blocks.AIR).defaultBlockState(), 3)
        }
        for (z in -3..3) for (y in 1..3) level.setBlockAndUpdate(origin.offset(6, y, z), Blocks.STONE.defaultBlockState())
        movePlayer(14.5, 1.0, 0.5)
        val summoned = service.summon(NpcSummonRequest(player.uuid, "FollowProof", level.dimension().location().toString(), position(0.5, 1.0, 0.5), -90.0F))
        check(summoned.result.status == NpcActionStatus.SUCCEEDED) { summoned.result.detail }
        npcUuid = checkNotNull(summoned.handle).npcUuid
    }

    fun tick() {
        if (outcome != null) return
        val npc = checkNotNull(service.find(npcUuid)?.let(service::runtime))
        val snapshot = npc.snapshot()
        check(++totalTicks < 2200) { "follow scenario timed out phase=$phase position=${snapshot.position} diagnostic=${BehaviorRuntimeService.diagnostic(npcUuid)}" }
        if (!snapshot.onGround && phase == 0) return
        age++
        if (snapshot.navigation != null) sawRoute = true
        if (kotlin.math.abs(snapshot.position.z - origin.z - 0.5) > 3.5) sawDetour = true
        val distance = TaskNavigator.distanceSquared(snapshot.position, NpcPosition(player.x, player.y, player.z))
        when (phase) {
            0 -> {
                val before = initial
                if (before == null) initial = snapshot.position
                else check(TaskNavigator.distanceSquared(before, snapshot.position) < 0.01) { "new unassigned NPC did not stay idle" }
                check(snapshot.navigation == null && snapshot.control == null && BehaviorRuntimeService.assignedPacks(level.server, npcUuid).isEmpty())
                if (age >= 30) {
                    check(BehaviorRuntimeService.assignPacks(level.server, npcUuid, listOf("samcnpc:follow_summoner")).status == NpcActionStatus.SUCCEEDED)
                    advance()
                }
            }
            1 -> {
                check(age < 800) { "follow did not route around wall and close to three blocks: position=${snapshot.position} distance=$distance route=$sawRoute detour=$sawDetour" }
                if (distance <= 3.2 * 3.2 && snapshot.onGround && snapshot.navigation == null && snapshot.control == null) {
                    check(sawRoute && sawDetour) { "wall was bypassed without observed real navigation" }
                    settled = snapshot.position
                    player.teleportTo(level, snapshot.position.x + 4.0, snapshot.position.y, snapshot.position.z, 90.0F, 0.0F)
                    advance()
                }
            }
            2 -> {
                check(TaskNavigator.distanceSquared(checkNotNull(settled), snapshot.position) < 0.04 && snapshot.navigation == null && snapshot.control == null) { "follow oscillated inside its 3-to-5 block dead-zone" }
                if (age >= 50) {
                    for (x in 15..24) for (z in -2..2) for (y in 1..minOf(3, x - 14)) {
                        level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.STONE.defaultBlockState())
                    }
                    movePlayer(21.5, 4.0, 0.5)
                    advance()
                }
            }
            3 -> {
                check(age < 500) { "follow did not climb actual steps: position=${snapshot.position}" }
                if (distance <= 3.2 * 3.2 && snapshot.onGround && snapshot.navigation == null && snapshot.control == null) {
                    check(kotlin.math.abs(snapshot.position.y - origin.y - 4.0) < 0.05) { "horizontal proximity was mistaken for arrival on another level" }
                    settled = snapshot.position
                    movePlayer(90.5, 4.0, 0.5)
                    advance()
                }
            }
            4 -> {
                check(TaskNavigator.distanceSquared(checkNotNull(settled), snapshot.position) < 0.04 && snapshot.navigation == null && snapshot.control == null) { "lost summoner caused motion/teleportation" }
                if (age >= 50) {
                    movePlayer(12.5, 1.0, -6.5)
                    advance()
                }
            }
            5 -> {
                check(age < 150) { "follow did not resume after renewed summoner observation" }
                val action = snapshot.navigation
                if (action != null) {
                    previousAction = action.actionId
                    check(BehaviorRuntimeService.reload().accepted)
                    check(npc.snapshot().navigation == null && npc.snapshot().control == null) { "reload left an old follow route active" }
                    advance()
                }
            }
            6 -> {
                check(age < 150) { "follow did not rebuild after registry reload" }
                val action = snapshot.navigation
                if (action != null) {
                    check(action.actionId != previousAction) { "reload reused cancelled follow execution" }
                    check(BehaviorRuntimeService.assignPacks(level.server, npcUuid, emptyList()).status == NpcActionStatus.SUCCEEDED)
                    check(npc.snapshot().navigation == null && npc.snapshot().control == null)
                    advance()
                }
            }
            7 -> {
                check(snapshot.navigation == null && snapshot.control == null) { "unassigned follow restarted motion" }
                if (age >= 35 && snapshot.onGround) {
                    outcome = "Follow: initial idle -> actual route around 3-block wall -> 3/5 dead-zone -> three actual steps -> lost summoner stops without teleport -> observation resumes -> reload cancels/rebuilds route -> unassign stops; ticks=$totalTicks"
                }
            }
        }
    }

    private fun advance() { phase++; age = 0 }
    private fun position(x: Double, y: Double, z: Double) = NpcPosition(origin.x + x, origin.y + y, origin.z + z)
    private fun movePlayer(x: Double, y: Double, z: Double) {
        val target = position(x, y, z)
        player.teleportTo(level, target.x, target.y, target.z, 90.0F, 0.0F)
    }
}
