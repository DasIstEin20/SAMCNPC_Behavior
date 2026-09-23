package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskFrameInvariantTest {
    private val navigate = NavigateTaskDefinition("minecraft:overworld", NpcPosition(0.5, 64.0, 0.5))
    private val attack = AttackTaskDefinition("minecraft:overworld", UUID(0, 3), navigate.destination)

    @Test fun unrelatedRuntimeCannotBePassedToANewFrame() {
        val combat = assertNotNull(CombatTaskState.forDefinition(attack))
        assertFailsWith<IllegalArgumentException> { TaskFrame(UUID(0, 1), navigate, combat = combat) }
    }

    @Test fun mutableFrameMismatchIsRejectedBeforeEnteringTheTaskStore() {
        val store = TaskStore.load(CompoundTag().apply { putInt("version", 9); put("tasks", ListTag()) })
        val record = TaskRecord.start(UUID(0, 2), navigate, emptyList())
        record.primary.combat = assertNotNull(CombatTaskState.forDefinition(attack))
        val result = store.put(record)
        assertEquals(NpcActionStatus.REJECTED, result.status)
        assertNull(store.get(record.npcUuid))
    }

    @Test fun mismatchedInventoryIsStoppedWithoutInventingAnOutcomeAndSurvivesQuarantine() {
        val record = TaskRecord.start(UUID(0, 2), navigate, emptyList())
        val resources = HarvestResources(mapOf("minecraft:oak_log" to 3))
        record.primary.inventory = InventoryWorkState(resources, mapOf("minecraft:oak_log" to 1), 0, 20)
        record.advanceTime(1)
        assertEquals(TaskStatus.FAILED, record.status)
        assertEquals(TaskReason.STATE_MISMATCH, record.reason)
        assertTrue(record.detail.contains("unexpected inventory"))
        assertTrue(record.logistics.outcomes.isEmpty())
        assertEquals(mapOf("minecraft:oak_log" to 3), resources.retained())
        val raw = CompoundTag().apply {
            putInt("version", 9)
            put("tasks", ListTag().apply { add(TaskCodec.write(record)) })
        }
        val restored = TaskStore.load(raw)
        assertNull(restored.get(record.npcUuid))
        assertNotNull(restored.problemFor(record.npcUuid))
        assertEquals(raw, restored.save(CompoundTag()))
    }
}
