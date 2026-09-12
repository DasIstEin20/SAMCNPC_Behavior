package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.*
import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class CombatMissionLifecycleTest {
    private val world = CombatPolicyWorld()
    private val body = CombatPolicyBody(world)
    private val anchor = body.current.position
    private val filter = NpcEntityTypeFilter.of(setOf("minecraft:zombie"))
    private fun start(definition: TaskDefinition) = TaskRecord.start(body.npcUuid, definition, emptyList())
    private fun execution(record: TaskRecord) = TaskExecution(record.id, record.active.id)
    private fun tick(record: TaskRecord, execution: TaskExecution) = TaskCombatMission.tick(record, execution, body, world)

    @Test fun disappearingTargetsAndOtherAttackersDeathsNeverMeetAnAreaQuota() {
        val definition = AreaAttackTaskDefinition(world.dimensionId, anchor, 24.0, filter, 1, tactics = CombatTactics.LEGACY)
        val record = start(definition); val runtime = execution(record); val state = checkNotNull(record.primary.combat)
        val absent = world.enemy(3); tick(record, runtime); assertEquals(1, body.hits)
        world.entities.remove(absent.uuid); tick(record, runtime)
        assertEquals(0, state.defeatedTargets.size); assertEquals(TaskStatus.RUNNING, record.status)
        val external = world.enemy(4); tick(record, runtime)
        world.entities[external.uuid] = external.copy(alive = false, combat = external.combat?.copy(lastDamageSourceEntityUuid = UUID(0, 99)))
        tick(record, runtime); assertEquals(0, state.defeatedTargets.size)
        val ours = world.enemy(5); tick(record, runtime)
        world.entities[ours.uuid] = ours.copy(alive = false, combat = ours.combat?.copy(lastDamageSourceEntityUuid = body.npcUuid))
        tick(record, runtime)
        assertEquals(setOf(ours.uuid), state.defeatedTargets)
        assertEquals(TaskStatus.COMPLETED, record.status); assertEquals(TaskReason.AREA_CLEARED, record.reason)
        assertEquals(record.report(), TaskCodec.read(TaskCodec.write(record)).report())
    }
    @Test fun patrolMustObserveEveryArrivalDwellAndReturnAfterInterruption() {
        val second = NpcPosition(6.0, 64.0, 0.0)
        val definition = PatrolTaskDefinition(world.dimensionId, anchor, 24.0, listOf(anchor, second), rounds = 1, dwellTicks = 20, reaction = PatrolReaction.PASSIVE)
        val record = start(definition); var runtime = execution(record); val state = checkNotNull(record.primary.combat)
        tick(record, runtime); record.advanceTime(10); assertEquals(10, state.dwellTicks)
        record.pause(); record.advanceTime(100); assertEquals(10, state.dwellTicks); record.resume()
        assertNull(record.interrupt(NavigateTaskDefinition(world.dimensionId, second)))
        record.advanceTime(30); assertEquals(10, state.dwellTicks)
        record.completeActive(); runtime = execution(record)
        body.current = body.current.copy(position = second); tick(record, runtime)
        assertEquals(0, state.dwellTicks); assertFalse(state.waypointReached); assertEquals(anchor, body.routes.last().position)
        body.current = body.current.copy(position = anchor); tick(record, runtime); record.advanceTime(20); tick(record, runtime)
        assertEquals(1, state.patrolIndex)
        val saved = TaskCodec.write(record); val restored = TaskCodec.read(saved); assertEquals(saved, TaskCodec.write(restored))
        body.current = body.current.copy(position = second); tick(record, runtime); record.advanceTime(20); tick(record, runtime)
        assertTrue(state.returning); assertEquals(1, state.patrolRounds); assertEquals(TaskStatus.RUNNING, record.status)
        tick(record, runtime); assertEquals(anchor, body.routes.last().position); assertEquals(TaskStatus.RUNNING, record.status)
        body.current = body.current.copy(position = anchor); tick(record, runtime)
        assertEquals(TaskStatus.COMPLETED, record.status); assertEquals(TaskReason.PATROL_FINISHED, record.reason)
        assertEquals(record.report(), TaskCodec.read(TaskCodec.write(record)).report())
    }
    @Test fun defenseDutyHasFiniteReturnTimeAndMissingSubjectCancelsExplicitly() {
        val subject = world.enemy(2).copy(isPlayer = true); world.entities[subject.uuid] = subject
        val definition = DefendTaskDefinition(world.dimensionId, anchor, 24.0, subject.uuid, dutyTicks = 20)
        val record = start(definition); val runtime = execution(record)
        record.advanceTime(20); assertEquals(400, record.primary.remainingTicks); tick(record, runtime)
        assertEquals(TaskReason.DEFENSE_FINISHED, record.reason)
        assertEquals(record.report(), TaskCodec.read(TaskCodec.write(record)).report())
        val missing = start(definition); world.entities.remove(subject.uuid); tick(missing, execution(missing))
        assertEquals(TaskStatus.CANCELLED, missing.status); assertEquals(TaskReason.SUBJECT_UNAVAILABLE, missing.reason)
        assertEquals(missing.report(), TaskCodec.read(TaskCodec.write(missing)).report())
    }
    @Test fun v4CombatMigratesWithoutInventingTacticsEffectsOrRefreshingItsDeadline() {
        val definition = AttackTaskDefinition(world.dimensionId, UUID(0, 3), anchor, version = 1)
        val record = start(definition); record.advanceTime(123); record.pause()
        val legacy = TaskCodec.write(record)
        legacy.getCompound("reaction").remove("tactics")
        legacy.getList("frames", 10).getCompound(0).remove("combat")
        val old = file(4, legacy)
        val store = TaskStore.load(old); val restored = assertNotNull(store.get(body.npcUuid))
        assertEquals(477, restored.primary.remainingTicks); assertEquals(TaskStatus.PAUSED, restored.status)
        assertEquals(CombatTactics.LEGACY, (restored.primary.definition as AttackTaskDefinition).tactics)
        assertFalse(checkNotNull(restored.primary.combat).attackSubmitted)
        assertEquals(8, store.save(CompoundTag()).getInt("version"))
        val future = legacy.copy(); future.getList("frames", 10).getCompound(0).put("combat", CombatTacticsCodec.writeState(CombatTaskState()))
        val invalid = file(4, future); val rejected = TaskStore.load(invalid)
        assertNull(rejected.get(body.npcUuid)); assertEquals(invalid, rejected.save(CompoundTag()))
    }
    @Test fun forgedProgressUnknownFieldsAndUnreturnedMissionCannotLoadAsComplete() {
        val record = start(AreaAttackTaskDefinition(world.dimensionId, anchor, 24.0, filter, 2))
        val original = TaskCodec.write(record)
        val missing = original.copy(); missing.getList("frames", 10).getCompound(0).remove("combat")
        val extra = original.copy(); extra.getList("frames", 10).getCompound(0).getCompound("combat").putString("command", "forbidden")
        val wrong = original.copy(); wrong.putString("status", "COMPLETED"); wrong.putString("reason", "AREA_CLEARED")
        val ledger = original.copy(); ledger.getList("frames", 10).getCompound(0).getCompound("combat").putInt("patrolIndex", 1)
        for (entry in listOf(missing, extra, wrong, ledger)) assertFailsWith<IllegalArgumentException> { TaskCodec.read(entry) }
        val state = checkNotNull(record.primary.combat); state.defeatedTargets.addAll(listOf(UUID(0, 3), UUID(0, 4))); state.returning = true
        record.finish(TaskStatus.COMPLETED, TaskReason.AREA_CLEARED, "forged physical return")
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(TaskCodec.write(record)) }
    }
    private fun file(version: Int, entry: CompoundTag) = CompoundTag().apply { putInt("version", version); put("tasks", ListTag().apply { add(entry) }) }
}
