package io.samcnpc.behavior.model

import io.samcnpc.core.api.NpcActionResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BehaviorArbiterTest {
    @Test
    fun `priority then stable identifiers decide one channel winner`() {
        val look = action("samcnpc:look_at_summoner", setOf(BehaviorChannel.LOOK))
        val lower = intent("samcnpc:alpha", 10, "low", 20, 0, look)
        val higher = intent("samcnpc:beta", 10, "high", 21, 0, look)

        assertEquals(listOf(higher), BehaviorArbiter.choose(listOf(lower, higher)))
    }

    @Test
    fun `multi channel action reserves all claimed channels atomically`() {
        val attack = action("samcnpc:attack_target", setOf(BehaviorChannel.COMBAT, BehaviorChannel.MAIN_HAND))
        val hand = action("samcnpc:use_main_hand", setOf(BehaviorChannel.MAIN_HAND))
        val selected = BehaviorArbiter.choose(
            listOf(
                intent("samcnpc:combat", 0, "attack", 100, 0, attack),
                intent("samcnpc:item", 0, "use", 90, 0, hand),
            ),
        )

        assertEquals(listOf("samcnpc:attack_target"), selected.map { it.action.actionId })
    }

    @Test
    fun `pack id is a deterministic tie breaker`() {
        val look = action("samcnpc:look_at_summoner", setOf(BehaviorChannel.LOOK))
        val beta = intent("samcnpc:beta", 0, "same", 0, 0, look)
        val alpha = intent("samcnpc:alpha", 0, "same", 0, 0, look)

        assertEquals(listOf(alpha), BehaviorArbiter.choose(listOf(beta, alpha)))
    }

    private fun action(id: String, channels: Set<BehaviorChannel>) = CompiledAction(id, { _, _, _ -> NpcActionResult.succeeded("test action") }, channels)

    private fun intent(pack: String, packPriority: Int, rule: String, rulePriority: Int, index: Int, action: CompiledAction) =
        ActionIntent(pack, packPriority, rule, rulePriority, index, action)
}
