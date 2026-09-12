package io.samcnpc.behavior.task

import io.samcnpc.behavior.combat.CombatPolicyWorld
import io.samcnpc.behavior.kernel.inventory.*
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Controlled observations exercise attribution failures; real container behavior has Forge scenes. */
class ContainerTransferKernelTest {
    @Test fun onlyMatchingPhysicalDeltasCanConfirmPartialSuccess() {
        val fixture = Fixture()
        val result = ContainerTransferKernel.transfer(fixture.body, fixture.world, fixture.position, fixture.item, 10, ContainerTransferDirection.WITHDRAW)
        assertEquals(3, result.observation?.moved); assertEquals(true, result.observation?.partial)
        assertEquals(1, fixture.calls); assertEquals(3, fixture.carried); assertEquals(7, fixture.stock)
        val denied = Fixture().apply { rejected = true; mismatch = true }
        val uncertain = ContainerTransferKernel.transfer(denied.body, denied.world, denied.position, denied.item, 10, ContainerTransferDirection.WITHDRAW)
        assertNull(uncertain.observation); assertEquals(ContainerTransferProblem.UNCERTAIN, uncertain.problem)
        assertEquals(1, denied.calls)
    }
    @Test fun changedShapeAndFalseSuccessfulNoOpCannotBecomeCargo() {
        for (change in listOf<(Fixture) -> Unit>({ it.changeShape = true }, { it.noOp = true }, { it.mismatch = true })) {
            val fixture = Fixture(); change(fixture)
            val result = ContainerTransferKernel.transfer(fixture.body, fixture.world, fixture.position, fixture.item, 10, ContainerTransferDirection.WITHDRAW)
            assertNull(result.observation); assertEquals(ContainerTransferProblem.UNCERTAIN, result.problem); assertEquals(1, fixture.calls)
        }
        val empty = Fixture().apply { stock = 0 }
        assertEquals(ContainerTransferProblem.SOURCE_EMPTY, ContainerTransferKernel.transfer(empty.body, empty.world, empty.position, empty.item, 10, ContainerTransferDirection.WITHDRAW).problem)
        assertEquals(0, empty.calls)
    }
    private class Fixture {
        val item = "minecraft:oak_log"
        val position = NpcBlockPosition(0, 64, 0)
        var carried = 0; var stock = 10; var calls = 0
        var mismatch = false; var rejected = false; var changeShape = false; var noOp = false
        private fun stack(count: Int) = NpcItemStackSnapshot(if (count == 0) null else item, count, 64, 0, 0)
        private fun knowledge(count: Int) = if (count == 0) NpcItemKnowledge.EMPTY else NpcItemKnowledge(item, emptySet())
        val world = object : NpcWorldView by CombatPolicyWorld() {
            override fun observeBlock(position: NpcBlockPosition) = NpcBlockObservation(position, "minecraft:chest", false, true, true)
            override fun observeBlockContainer(position: NpcBlockPosition) = NpcBlockContainerObservation(position,
                if (changeShape && calls > 0) 2 else 1, listOf(NpcBlockContainerSlotObservation(0, stack(stock), knowledge(stock))))
        }
        val body = object : TestNpcFacade() {
            override fun inventoryContents() = listOf(NpcInventoryEntry(0, stack(carried), knowledge(carried)))
            override fun moveBlockContainerToInventory(source: NpcBlockContainerSlot, count: Int): NpcActionResult {
                calls++
                if (!noOp) { carried += 3; stock -= if (mismatch) 2 else 3 }
                return if (rejected) NpcActionResult.rejected("controlled rejection") else NpcActionResult.succeeded("controlled counts")
            }
        }
    }
}
