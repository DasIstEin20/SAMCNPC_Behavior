package io.samcnpc.behavior.task

import io.samcnpc.behavior.api.ItemQuery
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class PreparationCooldownTest {
    private val origin = NpcPosition(0.5, 64.0, 0.5)
    private var item = NpcItemStackSnapshot.EMPTY
    private val npc = object : TestNpcFacade() {
        override fun inventoryContents() = (0..35).map { NpcInventoryEntry(it, if (it == 0) item else NpcItemStackSnapshot.EMPTY) }
        override fun equipmentContents() = NpcEquipmentSnapshot(item, NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY,
            NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY, NpcItemStackSnapshot.EMPTY)
        override fun equipmentKnowledge() = NpcEquipmentKnowledge.EMPTY
    }
    private fun record() = TaskRecord.start(UUID(0, 300), NavigateTaskDefinition("minecraft:overworld", origin, budget=TaskBudget(200)), emptyList()).also {
        it.logistics.policy = TaskLogisticsPolicy(anchor=origin, preparation=EnsureItems(ItemQuery.Exact("minecraft:coal"), 2, null))
        it.logistics.cooldownRemaining = 20
    }

    @Test fun missingPreparationWaitsOnlyForTheOriginalCooldownAndKeepsDeadlineAcrossReload() {
        val before = record()
        item = NpcItemStackSnapshot("minecraft:charcoal", 2, 64, 0, 0)
        assertTrue(TaskLogistics.waitsForPreparation(before, npc))
        before.advanceTime(5)
        val restored = TaskCodec.read(TaskCodec.write(before))
        assertEquals(15, restored.logistics.cooldownRemaining)
        assertEquals(195, restored.primary.remainingTicks)
        assertTrue(TaskLogistics.waitsForPreparation(restored, npc))
        restored.advanceTime(15)
        assertFalse(TaskLogistics.waitsForPreparation(restored, npc))
        assertEquals(180, restored.primary.remainingTicks)
        assertEquals(0, restored.primary.failures)
    }

    @Test fun satisfiedPreparationOrNoPolicyNeverStallsOtherwiseReadyWork() {
        val task = record()
        item = NpcItemStackSnapshot("minecraft:coal", 2, 64, 0, 0)
        assertFalse(TaskLogistics.waitsForPreparation(task, npc))
        item = NpcItemStackSnapshot.EMPTY
        task.logistics.policy = TaskLogisticsPolicy()
        assertFalse(TaskLogistics.waitsForPreparation(task, npc))
        assertEquals(20, task.logistics.cooldownRemaining)
    }

    @Test fun pausingDoesNotConsumeCooldownOrInventReadiness() {
        val task = record()
        assertTrue(task.pause())
        task.advanceTime(20)
        assertFalse(TaskLogistics.waitsForPreparation(task, npc))
        assertEquals(20, task.logistics.cooldownRemaining)
        assertEquals(200, task.primary.remainingTicks)
    }
}
