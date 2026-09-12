package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatPolicyWorld
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoJob
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class LumberjackSupplyPolicyTest {
    private val npc = UUID(0, 1)
    private val output = NpcBlockPosition(10, 64, 0)
    private val source = NpcBlockPosition(2, 64, 0)
    private val definition = LumberjackTaskDefinition("minecraft:overworld", WorkArea(WorkBox(NpcBlockPosition(4, 64, -2), NpcBlockPosition(8, 70, 2))),
        WoodSelection(listOf("samcnpc:oak")), output, 3, version = 2, supplySources = ContainerChoices(listOf(source)))
    private fun record(definition: LumberjackTaskDefinition = this.definition): TaskRecord {
        val selected = definition.supplySources?.positions?.first()
        val job = LumberjackDemoJob(npc, definition.dimensionId, selected ?: output, NpcBlockPosition(6, 64, 0), emptyList(), LumberjackDemoPhase.PREPARE_EQUIPMENT,
            0, 0, false, null, null, null, null, mutableListOf(), false, 0, 0, false, false, 0, false, emptyMap())
        val supplies = LumberjackSupplyState(selected)
        if (selected != null) supplies.checkpoints[selected] = LumberjackSupplyCheckpoint("minecraft:chest", 27, mapOf("minecraft:iron_axe" to 1))
        return TaskRecord.start(npc, definition, emptyList()).also {
            it.primary.lumberjack = LumberjackTaskState(job, HarvestResources(emptyMap()), emptyMap(), 27, supplies = supplies)
        }
    }
    @Test fun distinctSourcePhaseAndCarriedOnlyPermissionSurviveReload() {
        val task = record(); task.advanceTime(20); task.pause()
        val saved = TaskCodec.write(task); val restored = TaskCodec.read(saved)
        assertEquals(saved, TaskCodec.write(restored)); assertEquals(5980, restored.primary.remainingTicks)
        val state = assertNotNull(restored.primary.lumberjack)
        assertEquals(source, state.supplies?.selected); assertEquals(source, state.job.chestPosition)
        assertTrue(state.job.externalSuppliesAllowed)
        state.job.phase = LumberjackDemoPhase.SEARCH_WOOD
        LumberjackSupply.bind(definition, state)
        assertEquals(output, state.job.chestPosition)
        assertEquals(TaskCodec.write(restored), TaskCodec.write(TaskCodec.read(TaskCodec.write(restored))))
        val carried = TaskCodec.read(TaskCodec.write(record(definition.copy(supplySources = null))))
        assertFalse(assertNotNull(carried.primary.lumberjack).job.externalSuppliesAllowed)
    }
    @Test fun outputDoesNotAuthorizeWithdrawalAndCarriedOnlyDeniesEveryExternalSource() {
        val body = object : TestNpcFacade() {}
        val world = CombatPolicyWorld()
        val guarded = LumberjackTaskGuard(body, world, definition, assertNotNull(record().primary.lumberjack))
        assertEquals(NpcActionStatus.REJECTED, guarded.moveBlockContainerToInventory(NpcBlockContainerSlot(output, 0), 1).status)
        val carriedDefinition = definition.copy(supplySources = null)
        val carried = LumberjackTaskGuard(body, world, carriedDefinition, assertNotNull(record(carriedDefinition).primary.lumberjack))
        assertEquals(NpcActionStatus.REJECTED, carried.moveBlockContainerToInventory(NpcBlockContainerSlot(source, 0), 1).status)
        assertEquals(NpcActionStatus.REJECTED, carried.moveInventoryToBlockContainer(0, NpcBlockContainerSlot(source, 0), 1).status)
    }
    @Test fun unauthorizedSelectionsAndFutureSupplyStateDoNotLoad() {
        val valid = TaskCodec.write(record())
        val selectedElsewhere = valid.copy()
        val state = selectedElsewhere.getList("frames", 10).getCompound(0).getCompound("lumberjack")
        state.getCompound("supplies").getCompound("selected").putInt("x", 15)
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(selectedElsewhere) }
        val old = valid.copy(); old.getCompound("reaction").remove("tactics")
        assertFailsWith<IllegalArgumentException> { TaskCodec.read(old, 4) }
        assertNotNull(definition.copy(version = 1).validationProblem())
        assertNotNull(definition.copy(supplySources = ContainerChoices(listOf(NpcBlockPosition(80, 64, 0)))).validationProblem())
    }
}
