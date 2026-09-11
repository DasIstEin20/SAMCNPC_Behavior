package io.samcnpc.behavior.runtime

import com.google.gson.JsonObject
import io.samcnpc.behavior.model.ActionHandler
import io.samcnpc.behavior.model.BehaviorChannel
import io.samcnpc.behavior.model.BehaviorReadContext
import io.samcnpc.behavior.registry.ActionDefinition
import io.samcnpc.behavior.task.TaskNavigator
import io.samcnpc.core.api.*
import java.util.UUID

/** Transient companion policy; selected through the normal pack compiler and channel scope. */
internal object FollowMovement {
    const val ACTION_ID = "samcnpc:move_to_summoner"
    private const val REPATH_TICKS = 20
    private const val STALL_TICKS = 120
    private const val RETRY_TICKS = 20
    private const val ATTEMPTS = 3
    private const val LEG_TICKS = 1200
    private val states = mutableMapOf<UUID, Run>()

    internal data class Settings(val speed: Float, val stopDistance: Double, val startDistance: Double = stopDistance + 2.0)
    private class Run(val subject: UUID, val settings: Settings, now: Long) {
        var following = false
        var attempts = 0
        var waitUntil = 0L
        var route: NpcNavigationRequest? = null
        var navigationId: UUID? = null
        var lastRepath = now
        var lastProgress = now
        var started = now
        var lastTick = now
        var bestDistance = Double.POSITIVE_INFINITY
        var failedTarget: NpcPosition? = null
    }

    fun definition() = ActionDefinition(ACTION_ID, setOf(BehaviorChannel.MOVEMENT, BehaviorChannel.LOOK), ::validate) { args ->
        val stop = args.get("stopDistance").asDouble
        val settings = Settings(args.get("speed").asFloat, stop, args.get("startDistance")?.asDouble ?: stop + 2.0)
        ActionHandler { npc, _, context -> tick(npc, context, settings) }
    }

    internal fun tick(npc: NpcFacade, context: BehaviorReadContext, settings: Settings): NpcActionResult {
        val snapshot = context.snapshot
        val subject = context.summoner
        if (subject == null || !subject.alive || !subject.isPlayer || subject.uuid != snapshot.summonerUuid) {
            remove(npc.npcUuid)
            stop(npc, snapshot)
            return NpcActionResult.running("follow waiting: summoner is unavailable in the bounded world view")
        }
        val now = snapshot.gameTime
        var run = states[npc.npcUuid]
        if (run == null || run.subject != subject.uuid || run.settings != settings || now < run.lastTick) {
            run = Run(subject.uuid, settings, now)
            states[npc.npcUuid] = run
        }
        run.lastTick = now
        val look = npc.lookAtEntity(subject.uuid)
        if (look.status in FAILURE) {
            stop(npc, snapshot)
            return look
        }
        val distance = TaskNavigator.distanceSquared(snapshot.position, subject.position)
        val stopDistance = settings.stopDistance.coerceAtLeast(0.25)
        if (distance <= stopDistance * stopDistance || (!run.following && distance <= settings.startDistance * settings.startDistance)) {
            run.following = false
            run.route = null
            run.navigationId = null
            run.attempts = 0
            stop(npc, snapshot)
            return NpcActionResult.succeeded("follow holding its configured dead-zone")
        }
        if (!run.following) {
            run.following = true
            run.started = now
            run.lastProgress = now
        }
        if (run.attempts >= ATTEMPTS) {
            val failedTarget = run.failedTarget
            if (failedTarget == null || TaskNavigator.distanceSquared(failedTarget, subject.position) < 4.0) {
                return NpcActionResult.failed("follow route attempts exhausted; waiting for summoner displacement of two blocks or reassignment", NpcActionCode.NO_PROGRESS)
            }
            run.attempts = 0
            run.started = now
            run.lastProgress = now
            run.waitUntil = now
        }
        if (now < run.waitUntil) return NpcActionResult.running("follow waiting before its next bounded route attempt")
        if (now - run.started >= LEG_TICKS) {
            run.attempts = ATTEMPTS - 1
            return failedAttempt(npc, snapshot, run, subject.position, "follow approach reached its 1200-tick deadline")
        }
        val completed = snapshot.recentCompletions.firstOrNull { it.result.actionId == run.navigationId }
        if (run.navigationId != null && completed != null) {
            return failedAttempt(npc, snapshot, run, subject.position, "follow route ended before physical arrival: ${completed.result.code}")
        }
        val oldRoute = run.route
        val repath = oldRoute == null || (now - run.lastRepath >= REPATH_TICKS &&
            TaskNavigator.distanceSquared(oldRoute.position, subject.position) >= 0.25)
        if (repath) {
            run.route = NpcNavigationRequest(subject.position, settings.speed, stopDistance.coerceIn(0.25, 1.5))
            run.lastRepath = now
            run.bestDistance = distance
            // A moving destination can rebase distance, but cannot renew a stuck body's deadline.
        }
        val route = checkNotNull(run.route)
        val routeDistance = TaskNavigator.distanceSquared(snapshot.position, route.position)
        if (routeDistance < run.bestDistance - 0.0625) {
            run.bestDistance = routeDistance
            run.lastProgress = now
        }
        if (now - run.lastProgress >= STALL_TICKS) {
            return failedAttempt(npc, snapshot, run, subject.position, "follow made no physical progress for 120 ticks")
        }
        val result = npc.navigateTo(route)
        if (result.status in FAILURE || result.status == NpcActionStatus.SUCCEEDED) {
            return failedAttempt(npc, snapshot, run, subject.position, "follow could not continue its supplied route: ${result.code}")
        }
        run.navigationId = result.actionId
        return result
    }

    fun remove(npcUuid: UUID) { states.remove(npcUuid) }
    fun clearAll() { states.clear() }

    private fun failedAttempt(npc: NpcFacade, snapshot: NpcSnapshot, run: Run, target: NpcPosition, detail: String): NpcActionResult {
        stop(npc, snapshot)
        run.navigationId = null
        run.route = null
        run.attempts++
        run.waitUntil = snapshot.gameTime + RETRY_TICKS
        run.lastProgress = run.waitUntil
        run.failedTarget = target
        return NpcActionResult.failed("$detail; attempt ${run.attempts}/$ATTEMPTS", NpcActionCode.NO_PROGRESS)
    }

    private fun stop(npc: NpcFacade, snapshot: NpcSnapshot) {
        if (snapshot.navigation != null || snapshot.control != null) npc.stopControl()
    }

    private fun validate(args: JsonObject): String? {
        fun number(key: String): Double? {
            val element = args.get(key) ?: return null
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
            return element.asDouble.takeIf { it.isFinite() }
        }
        val speed = number("speed") ?: return "requires finite numeric speed"
        val stop = number("stopDistance") ?: return "requires finite numeric stopDistance"
        val start = if (args.has("startDistance")) number("startDistance") ?: return "requires finite numeric startDistance" else stop + 2.0
        return when {
            args.keySet().any { it !in setOf("speed", "stopDistance", "startDistance") } -> "accepts only speed, stopDistance and optional startDistance"
            speed !in 0.1..1.5 -> "follow speed must be in [0.1, 1.5]"
            stop !in 0.0..16.0 -> "follow stopDistance must be in [0, 16]"
            start <= stop || start !in 0.25..32.0 -> "follow startDistance must exceed stopDistance and be in [0.25, 32]"
            else -> null
        }
    }
    private val FAILURE = setOf(NpcActionStatus.REJECTED, NpcActionStatus.FAILED, NpcActionStatus.UNSUPPORTED)
}
