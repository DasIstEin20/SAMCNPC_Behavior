package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatPolicyWorld
import io.samcnpc.behavior.lumberjack.LumberjackDeferredWork
import io.samcnpc.behavior.lumberjack.model.*
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class DeferredWoodTest {
    private val target = NpcBlockPosition(6, 68, 5)
    private val blocker = NpcBlockPosition(7, 67, 5)
    private val definition = LumberjackTaskDefinition("minecraft:overworld", WorkArea(WorkBox(NpcBlockPosition(0, 64, 0), NpcBlockPosition(10, 75, 10))),
        WoodSelection(listOf("samcnpc:dark_oak")), NpcBlockPosition(0, 64, 0), 8)
    @Test fun blockedBranchIsReconsideredOnlyAfterItsObstructionIsGoneAndOnlyOnceAcrossReload() {
        val job = LumberjackDemoJob(UUID(0, 1), definition.dimensionId, definition.destination, definition.destination, emptyList(), LumberjackDemoPhase.SEARCH_WOOD,
            definition.area.columns, 0, false, target, target, null, null, mutableListOf(), false, 0, 0, false, false, 1, false, emptyMap())
        job.workSelection = LumberjackWorkSelection(definition.area, definition.wood); job.deferredWoodEnabled = true
        val blocks = mutableMapOf(target to "minecraft:dark_oak_log", blocker to "minecraft:dark_oak_log")
        val world = object : NpcWorldView by CombatPolicyWorld() {
            override fun observeBlock(position: NpcBlockPosition): NpcBlockObservation {
                val id = blocks[position] ?: "minecraft:air"; return NpcBlockObservation(position, id, id == "minecraft:air", id != "minecraft:air", false)
            }
        }
        LumberjackDeferredWork.record(job, world, blocker)
        LumberjackDeferredWork.record(job, world, blocker)
        assertEquals(1, job.deferredWood.size)
        assertNull(LumberjackDeferredWork.candidate(job, world))
        blocks[blocker] = "minecraft:stone"
        assertNull(LumberjackDeferredWork.candidate(job, world))
        blocks.remove(blocker)
        val candidate = assertNotNull(LumberjackDeferredWork.candidate(job, world))
        assertEquals(target, candidate.target); assertEquals(2, candidate.failedAttempts)
        blocks[target] = "minecraft:oak_log"
        assertNull(LumberjackDeferredWork.candidate(job, world))
        blocks[target] = "minecraft:dark_oak_log"
        candidate.revisited = true
        val record = TaskRecord.start(job.npcUuid, definition, emptyList())
        record.primary.lumberjack = LumberjackTaskState(job, HarvestResources(emptyMap()), emptyMap(), 27)
        record.advanceTime(123)
        val restored = TaskCodec.read(TaskCodec.write(record)); val loaded = assertNotNull(restored.primary.lumberjack).job
        loaded.workSelection = job.workSelection
        assertEquals(5877, restored.primary.remainingTicks)
        assertEquals(job.deferredWood, loaded.deferredWood)
        assertNull(LumberjackDeferredWork.candidate(loaded, world))
        LumberjackDeferredWork.record(loaded, world, blocker)
        assertEquals(1, loaded.deferredWood.size)
    }
}
