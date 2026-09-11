package io.samcnpc.behavior.gametest

import com.mojang.logging.LogUtils
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object BehaviorDecisionMeasurementGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", batch = "decision_micro_cost")
    fun disabledDebugDecisionCostUsesTheRealRuntimeWithFixedIdleFacts(helper: GameTestHelper) {
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(BlockPos(2, 1, 2))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5)
        body.setNoGravity(true)
        check(helper.level.addFreshEntity(body))
        val server = helper.level.server
        try {
            val service = CoreNpcApi.service(server)
            val handle = checkNotNull(service.find(body.uuid))
            val npc = checkNotNull(service.runtime(handle))
            check(BehaviorRuntimeService.assignPacks(server, body.uuid, listOf(
                "samcnpc:idle_look", "samcnpc:follow_summoner", "samcnpc:retaliate")).status == NpcActionStatus.SUCCEEDED)
            val event = NpcServerTickEvent(handle, npc.snapshot(), npc, npc.worldView())
            repeat(5000) { BehaviorRuntimeService.tick(event) }
            val before = checkNotNull(BehaviorRuntimeService.diagnostic(body.uuid)).work
            val batches = LongArray(24)
            for (batch in batches.indices) {
                val start = System.nanoTime()
                repeat(1000) { BehaviorRuntimeService.tick(event) }
                batches[batch] = (System.nanoTime() - start) / 1000
            }
            val after = checkNotNull(BehaviorRuntimeService.diagnostic(body.uuid)).work
            check(after.decisions - before.decisions == 24_000L)
            // The maintained follow pack now has one stateful rule; idle + follow + retaliation = four.
            check(after.evaluatedRules - before.evaluatedRules == 96_000L)
            check(after.executedIntents == before.executedIntents && after.lastDecisionNanos == null)
            batches.sort()
            LogUtils.getLogger().info(
                "SAMCNPC DECISION MICRO COST: profiling=off packs=3 rules=4 measured=24000 warmup=5000 " +
                    "batchMeanMedianNs={} batchMeanP95Ns={} batchMeanMaxNs={} " +
                    "scope=fixed idle facts; excludes Core snapshot/physics/world query cost; not server MSPT",
                batches[12], batches[22], batches[23])
            helper.succeed()
        } finally {
            BehaviorRuntimeService.assignPacks(server, body.uuid, emptyList())
            body.discard()
        }
    }
}
