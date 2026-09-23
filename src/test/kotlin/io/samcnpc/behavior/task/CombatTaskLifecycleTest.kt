package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class CombatTaskLifecycleTest {
    private val npc = UUID(0, 1)
    private val target = UUID(0, 3)
    private fun primary() = TaskRecord.start(npc, NavigateTaskDefinition("minecraft:overworld", NpcPosition(8.0, 64.0, 0.0), budget = TaskBudget(ticks = 600)), emptyList())
    private fun attack(ticks: Int = 100) = AttackTaskDefinition("minecraft:overworld", target, NpcPosition(0.0, 64.0, 0.0), budget = TaskBudget(ticks = ticks))

    @Test fun endedCombatRetainsThePrimaryIntentDeadlineAndAnExplicitOutcome() {
        val record = primary()
        val initial = record.primary.id
        record.reaction.policy = TaskReactionPolicy(TaskReactionMode.RETALIATE)
        assertNull(record.interrupt(attack()))
        assertNotNull(record.interrupt(attack()))
        record.advanceTime(35)
        record.endCombat(TaskStatus.CANCELLED, TaskReason.TARGET_UNAVAILABLE, 0, "target unloaded")
        assertEquals(initial, record.active.id)
        assertEquals(565, record.primary.remainingTicks)
        assertEquals(1, record.completedInterruptions)
        assertEquals(TaskReason.RESUMED, record.reason)
        assertEquals(40, record.reaction.cooldownRemaining)
        assertEquals(0, record.lastCombat?.confirmedKills)
        val restored = TaskCodec.read(TaskCodec.write(record))
        assertEquals(record.report(), restored.report())
        assertEquals(record.reaction.policy, restored.reaction.policy)
        assertEquals(40, restored.reaction.cooldownRemaining)
    }

    @Test fun reactionDeadlineReturnsToWorkButTheOriginalTaskDeadlineRemainsHard() {
        val record = primary()
        assertNull(record.interrupt(attack(40)))
        record.advanceTime(40)
        assertEquals(TaskStatus.RUNNING, record.status)
        assertEquals(560, record.primary.remainingTicks)
        assertEquals(TaskReason.COMBAT_TIME_LIMIT, record.lastCombat?.reason)
        assertEquals(1, record.frames.size)
        assertNull(record.interrupt(attack(600)))
        record.advanceTime(560)
        assertEquals(TaskStatus.FAILED, record.status)
        assertEquals(TaskReason.TIME_LIMIT, record.reason)
        assertEquals(0, record.primary.remainingTicks)
    }

    @Test fun exhaustedCombatAttemptsCannotRenewOrFailTheSuspendedPrimary() {
        val record = primary()
        assertNull(record.interrupt(attack()))
        repeat(3) {
            record.retry(TaskReason.NO_PROGRESS, "wall")
            if (it < 2) record.advanceTime(record.active.waitTicks)
        }
        assertEquals(TaskStatus.RUNNING, record.status)
        assertEquals(3, record.totalFailures)
        assertEquals(540, record.primary.remainingTicks)
        assertEquals(TaskReason.COMBAT_NO_PROGRESS, record.lastCombat?.reason)
        assertEquals(record.report(), TaskCodec.read(TaskCodec.write(record)).report())
    }

    @Test fun reactionsAreOptInAndOnlyNewDamageCanStartAnUninterruptedFrame() {
        val record = primary()
        val snapshot = decisionContext().snapshot.copy(lastDamageSourceEntityUuid = target, lastDamageAgeTicks = 0, lastDamageEventId = UUID.randomUUID())
        assertFalse(TaskCombatReactions.requested(record, snapshot))
        record.reaction.policy = TaskReactionPolicy(TaskReactionMode.RETALIATE)
        assertTrue(TaskCombatReactions.requested(record, snapshot))
        record.reaction.handledDamage = snapshot.lastDamageEventId
        assertFalse(TaskCombatReactions.requested(record, snapshot))
        val next = snapshot.copy(lastDamageEventId = UUID.randomUUID())
        assertTrue(TaskCombatReactions.requested(record, next))
        assertNull(record.interrupt(attack()))
        TaskCombatReactions.observe(record, next)
        assertFalse(TaskCombatReactions.requested(record, next))
        record.endCombat(TaskStatus.CANCELLED, TaskReason.LEASH_REACHED, 0, "leash")
        assertFalse(TaskCombatReactions.requested(record, next.copy(lastDamageEventId = UUID.randomUUID())))
    }

    @Test fun migrationDefaultsArePassiveAndFutureCombatCannotHideInAnOldFile() {
        for (version in 1..3) {
            val entry = TaskCodec.write(primary()).apply { remove("reaction") }
            val root = file(version, entry)
            val store = TaskStore.load(root)
            val restored = assertNotNull(store.get(npc))
            assertEquals(TaskReactionPolicy(), restored.reaction.policy)
            assertEquals(10, store.save(CompoundTag()).getInt("version"))
            val invalid = TaskCodec.write(TaskRecord.start(npc, attack(), emptyList()))
            val old = file(version, invalid)
            val rejected = TaskStore.load(old)
            assertNull(rejected.get(npc))
            assertEquals(old, rejected.save(CompoundTag()))
        }
        val missing = TaskCodec.write(primary()).apply { remove("reaction") }
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(missing) }
    }

    @Test fun combatCompletionNeedsConfirmedPhysicalKillMetadataAndNoRouteIsSaved() {
        val record = TaskRecord.start(npc, attack(), emptyList())
        val forged = TaskCodec.write(record).apply { putString("status", "COMPLETED"); putString("reason", "TARGET_DEFEATED") }
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(forged) }
        record.endCombat(TaskStatus.COMPLETED, TaskReason.TARGET_DEFEATED, 1, "confirmed hit")
        val tag = TaskCodec.write(record)
        assertEquals(record.report(), TaskCodec.read(tag).report())
        for (key in listOf("actionId", "navigationId", "combatRoute", "handledDamage", "entityReference")) assertFalse(tag.toString().contains(key))
        tag.getCompound("lastCombat").putInt("kills", 0)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(tag) }
    }

    private fun file(version: Int, entry: CompoundTag) = CompoundTag().apply { putInt("version", version); put("tasks", ListTag().apply { add(entry) }) }
}
