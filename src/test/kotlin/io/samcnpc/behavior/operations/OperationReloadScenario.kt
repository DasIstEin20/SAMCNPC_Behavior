package io.samcnpc.behavior.operations

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcPosition
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import java.nio.file.Files
import java.util.UUID

/** Exercises the existing disk loader while the same real navigation task remains active. */
internal class OperationReloadScenario(server: MinecraftServer) {
    val scene = OperationScene.create(server.overworld(), OperationKind.NAVIGATE, BlockPos(1000, 80, 1000))
    private val file = BehaviorRuntimeService.externalDirectory().resolve("o4_reload.json")
    private val packId = "samcnpc:o4_reload"
    private val originalIds = BehaviorRuntimeService.activePackIds()
    private var stage = 0
    private var age = 0
    private var ticks = 0
    private var taskId: UUID? = null
    private var oldAction: UUID? = null
    private var idlePosition: NpcPosition? = null
    private var lastRemaining = Int.MAX_VALUE
    var complete = false
        private set

    fun tick() {
        if (complete) return
        check(++ticks < 1200) { "Live reload scenario timed out at stage=$stage" }
        if (!scene.loaded || !scene.body.onGround()) return
        age++
        if (taskId != null) {
            check(scene.record.id == taskId && scene.record.primary.remainingTicks <= lastRemaining)
            lastRemaining = scene.record.primary.remainingTicks
            check(scene.record.status != TaskStatus.FAILED && scene.record.status != TaskStatus.CANCELLED) { scene.record.report() }
        }
        when (stage) {
            0 -> {
                check(!Files.exists(file)) { "Use a fresh operationsSmokeId; reload fixture already exists" }
                Files.createDirectories(file.parent)
                Files.writeString(file, document(-100))
                check(BehaviorRuntimeService.reload().accepted)
                check(BehaviorRuntimeService.activePackIds() == (originalIds + packId).sorted())
                scene.assign(NavigateTaskDefinition(scene.npc.snapshot().dimensionId, scene.point(26, 0), budget = TaskBudget(1200)))
                taskId = scene.record.id
                check(BehaviorRuntimeService.assignPacks(scene.server, scene.npcId,
                    listOf("samcnpc:task_navigation", packId)).status == NpcActionStatus.SUCCEEDED)
                advance()
            }
            1 -> {
                val navigation = scene.npc.snapshot().navigation ?: return
                if (TaskNavigator.distanceSquared(scene.npc.snapshot().position, scene.start) < 1.0) return
                oldAction = navigation.actionId
                val before = TaskCodec.write(scene.record)
                val ids = BehaviorRuntimeService.activePackIds()
                Files.writeString(file, "{\"schemaVersion\":1,\"id\":")
                val rejected = BehaviorRuntimeService.reload()
                check(!rejected.accepted && rejected.messages.any { it.contains("o4_reload.json") })
                check(BehaviorRuntimeService.activePackIds() == ids)
                check(scene.npc.snapshot().navigation?.actionId == oldAction) { "Rejected candidate cancelled valid work" }
                check(TaskCodec.write(scene.record) == before) { "Rejected candidate changed the task" }
                Files.writeString(file, document(-101))
                advance()
            }
            2 -> {
                if (age < 20) return
                check(scene.npc.snapshot().navigation?.actionId == oldAction)
                val remaining = scene.record.primary.remainingTicks
                check(BehaviorRuntimeService.reload().accepted)
                scene.requireReleased()
                check(scene.record.id == taskId && scene.record.primary.remainingTicks == remaining)
                advance()
            }
            3 -> {
                val navigation = scene.npc.snapshot().navigation ?: return
                check(navigation.actionId != oldAction) { "Accepted reload reused an old Core action" }
                Files.delete(file)
                check(BehaviorRuntimeService.reload().accepted)
                check(packId !in BehaviorRuntimeService.activePackIds())
                scene.requireReleased()
                advance()
            }
            4 -> {
                val diagnostic = checkNotNull(BehaviorRuntimeService.diagnostic(scene.npcId))
                check(diagnostic.lastProblem?.contains(packId) == true && diagnostic.lastProblem?.contains("safe idle") == true)
                scene.requireReleased()
                check(diagnostic.selectedIntents.isEmpty())
                if (age == 5) idlePosition = scene.npc.snapshot().position
                val idle = idlePosition
                if (idle != null) check(TaskNavigator.distanceSquared(idle, scene.npc.snapshot().position) <= 0.0025)
                if (age < 40) return
                Files.writeString(file, document(-102))
                check(BehaviorRuntimeService.reload().accepted)
                check(BehaviorRuntimeService.assignPacks(scene.server, scene.npcId,
                    listOf("samcnpc:task_navigation")).status == NpcActionStatus.SUCCEEDED)
                advance()
            }
            5 -> {
                if (!scene.record.status.terminal) return
                scene.requireCompleted()
                check(scene.record.id == taskId)
                check(TaskNavigator.distanceSquared(scene.npc.snapshot().position, scene.point(26, 0)) <= 0.75 * 0.75)
                Files.delete(file)
                check(BehaviorRuntimeService.reload().accepted)
                check(BehaviorRuntimeService.activePackIds() == originalIds)
                scene.close()
                complete = true
            }
        }
    }

    private fun advance() { stage++; age = 0 }

    private fun document(priority: Int) = """
        {
          "schemaVersion": 1,
          "id": "$packId",
          "description": "Isolated O4 lifecycle fixture using registered read-only selection.",
          "priority": $priority,
          "channels": ["look"],
          "rules": [{
            "id": "look_if_bound", "priority": 1,
            "when": {"test": {"condition": "samcnpc:has_summoner"}},
            "actions": [{"action": "samcnpc:look_at_summoner"}]
          }]
        }
    """.trimIndent()
}
