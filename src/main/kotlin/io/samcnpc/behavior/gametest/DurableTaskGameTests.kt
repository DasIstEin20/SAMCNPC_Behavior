package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object DurableTaskGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "durable_navigation")
    fun finiteNavigationPausesResumesAndReportsPhysicalArrival(helper: GameTestHelper) {
        val body = spawn(helper)
        val destination = position(helper, 9, 3)
        var phase = 0
        var age = 0
        var remaining = 0
        var settled: NpcPosition? = null
        var firstAction: UUID? = null
        var taskId: UUID? = null
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val npc = runtime(helper, body) ?: return@onEachTick
            val server = helper.level.server
            if (phase == 0) {
                check(BehaviorRuntimeService.assignPacks(server, body.uuid, listOf("samcnpc:idle_look")).status == NpcActionStatus.SUCCEEDED)
                check(TaskService.assign(server, npc, definition(helper, 9, 3)).status == NpcActionStatus.SUCCEEDED)
                taskId = checkNotNull(TaskStore.forServer(server).get(body.uuid)).id
                phase = 1
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            age++
            if (phase == 1 && age >= 5 && npc.snapshot().navigation != null) {
                firstAction = npc.snapshot().navigation?.actionId
                check(TaskService.pause(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                check(npc.snapshot().navigation == null && npc.snapshot().control == null)
                remaining = record.primary.remainingTicks
                phase = 2
                age = 0
            } else if (phase == 2) {
                check(record.status == TaskStatus.PAUSED && record.primary.remainingTicks == remaining)
                if (age == 12) settled = npc.snapshot().position
                if (age == 30) {
                    check(TaskNavigator.distanceSquared(checkNotNull(settled), npc.snapshot().position) < 0.0025) { "paused task kept moving after inertia settled" }
                    check(TaskService.resume(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                    phase = 3
                }
            } else if (phase == 3) {
                val navigation = npc.snapshot().navigation
                check(navigation == null || navigation.actionId != firstAction) { "resume reused stale Core navigation" }
                check(record.status != TaskStatus.FAILED) { TaskService.status(server, body.uuid).orEmpty() }
                if (record.status == TaskStatus.COMPLETED) {
                    check(record.id == taskId && TaskNavigator.distanceSquared(npc.snapshot().position, destination) <= 0.75 * 0.75)
                    check(npc.snapshot().navigation == null && npc.snapshot().control == null)
                    check(BehaviorRuntimeService.assignedPacks(server, body.uuid) == listOf("samcnpc:idle_look"))
                    finished = true
                    body.discard()
                    helper.succeed()
                }
            }
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 200, batch = "durable_blocked")
    fun unavailableDestinationExhaustsRetriesAndRetainsAnExplicitReport(helper: GameTestHelper) {
        val body = spawn(helper)
        helper.setBlock(BlockPos(7, 1, 3), Blocks.STONE)
        helper.setBlock(BlockPos(7, 2, 3), Blocks.STONE)
        var started = false
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val npc = runtime(helper, body) ?: return@onEachTick
            val server = helper.level.server
            if (!started) {
                check(TaskService.assign(server, npc, definition(helper, 7, 3)).status == NpcActionStatus.SUCCEEDED)
                started = true
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            check(npc.snapshot().navigation == null && npc.snapshot().control == null) { "unavailable target started movement" }
            if (record.status == TaskStatus.FAILED) {
                check(record.reason == TaskReason.RETRY_LIMIT && record.report().failures == 3)
                check(TaskService.status(server, body.uuid)?.contains("DESTINATION_UNAVAILABLE") == true)
                finished = true
                body.discard()
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 600, batch = "durable_interruptions")
    fun twoNestedWaypointsResumeThePrimaryAndRejectAThirdInterruption(helper: GameTestHelper) {
        val body = spawn(helper)
        var started = false
        var finished = false
        var previousDepth = 0
        var priorDestination: NpcPosition? = null
        helper.onEachTick {
            if (finished) return@onEachTick
            val npc = runtime(helper, body) ?: return@onEachTick
            val server = helper.level.server
            if (!started) {
                check(TaskService.assign(server, npc, definition(helper, 9, 3)).status == NpcActionStatus.SUCCEEDED)
                check(TaskService.interrupt(server, body.uuid, definition(helper, 6, 6)).status == NpcActionStatus.SUCCEEDED)
                check(TaskService.interrupt(server, body.uuid, definition(helper, 2, 5)).status == NpcActionStatus.SUCCEEDED)
                check(TaskService.interrupt(server, body.uuid, definition(helper, 3, 5)).status == NpcActionStatus.REJECTED)
                previousDepth = 3
                priorDestination = position(helper, 2, 5)
                started = true
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            check(record.status != TaskStatus.FAILED) { TaskService.status(server, body.uuid).orEmpty() }
            if (record.frames.size < previousDepth) {
                check(TaskNavigator.distanceSquared(npc.snapshot().position, checkNotNull(priorDestination)) <= 0.75 * 0.75) { "interruption completed without physical arrival" }
                previousDepth = record.frames.size
                priorDestination = (record.active.definition as NavigateTaskDefinition).destination
            }
            if (record.status == TaskStatus.COMPLETED) {
                check(record.report().completedInterruptions == 2 && record.frames.size == 1)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, position(helper, 9, 3)) <= 0.75 * 0.75)
                finished = true
                body.discard()
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 500, batch = "durable_replace")
    fun assignmentCancelsImmediatelyAndLateResultCannotCompleteTheReplacementTask(helper: GameTestHelper) {
        val body = spawn(helper)
        var phase = 0
        var oldId: UUID? = null
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val npc = runtime(helper, body) ?: return@onEachTick
            val server = helper.level.server
            if (phase == 0) {
                check(TaskService.assign(server, npc, definition(helper, 9, 3)).status == NpcActionStatus.SUCCEEDED)
                phase = 1
            }
            if (phase == 1 && npc.snapshot().navigation != null) {
                val actionId = checkNotNull(npc.snapshot().navigation).actionId
                val old = checkNotNull(TaskStore.forServer(server).get(body.uuid))
                oldId = old.id
                check(BehaviorRuntimeService.assignPacks(server, body.uuid, emptyList()).status == NpcActionStatus.SUCCEEDED)
                check(old.status == TaskStatus.CANCELLED && old.reason == TaskReason.ASSIGNMENT_CHANGED)
                check(npc.snapshot().navigation == null)
                check(TaskService.assign(server, npc, definition(helper, 6, 6)).status == NpcActionStatus.SUCCEEDED)
                MinecraftForge.EVENT_BUS.post(NpcActionCompletedEvent(NpcHandle(body.uuid, "test"), NpcActionResult.succeeded("delayed old arrival", actionId)))
                val replacement = checkNotNull(TaskStore.forServer(server).get(body.uuid))
                check(replacement.id != old.id && replacement.status == TaskStatus.RUNNING)
                phase = 2
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            check(record.status != TaskStatus.FAILED) { TaskService.status(server, body.uuid).orEmpty() }
            if (phase == 2 && record.status == TaskStatus.COMPLETED) {
                check(record.id != oldId && TaskNavigator.distanceSquared(npc.snapshot().position, position(helper, 6, 6)) <= 0.75 * 0.75)
                finished = true
                body.discard()
                helper.succeed()
            }
        }
    }

    private fun definition(helper: GameTestHelper, x: Int, z: Int) = NavigateTaskDefinition(
        helper.level.dimension().location().toString(), position(helper, x, z), budget = TaskBudget(ticks = 600),
    )
    private fun position(helper: GameTestHelper, x: Int, z: Int): NpcPosition {
        val absolute = helper.absolutePos(BlockPos(x, 1, z))
        return NpcPosition(absolute.x + 0.5, absolute.y.toDouble(), absolute.z + 0.5)
    }
    private fun runtime(helper: GameTestHelper, body: LivingEntity): NpcFacade? {
        if (!body.onGround()) return null
        val service = CoreNpcApi.service(helper.level.server)
        return service.find(body.uuid)?.let(service::runtime)
    }
    private fun spawn(helper: GameTestHelper): LivingEntity {
        for (x in 0..11) for (z in 0..8) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = position(helper, 1, 3)
        body.moveTo(feet.x, feet.y, feet.z, -90.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        return body
    }
}
