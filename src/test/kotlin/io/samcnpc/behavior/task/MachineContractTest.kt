package io.samcnpc.behavior.task

import io.samcnpc.behavior.command.TaskMachineCommands
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class MachineContractTest {
    private val dimension="minecraft:overworld"
    private val anchor=NpcPosition(0.5,65.0,0.5)
    private val at=NpcContainerEndpoint(dimension,NpcBlockPosition(8,65,0))
    private val ore="minecraft:raw_iron"
    private val ingot="minecraft:iron_ingot"
    private fun definition() = MachineTaskDefinition(dimension,MachineFeeds(listOf(MachinePort(at.copy(side=NpcBlockFace.UP),0,ore,4))),
        MachinePort(at.copy(side=NpcBlockFace.DOWN),0,ingot,4),anchor,noProgressTicks=200,budget=TaskBudget(1000))
    @Test fun fixedPortsAreImmutableAndCannotSelfCreditInputAsOutput() {
        val list=mutableListOf(MachinePort(at,0,ore,4));val feeds=MachineFeeds(list);list.clear();assertEquals(1,feeds.ports.size)
        val d=definition();assertNull(d.validationProblem())
        assertNotNull(d.copy(output=d.output.copy(itemId=ore)).validationProblem())
        assertNotNull(d.copy(feeds=MachineFeeds(List(5) { d.feeds.ports.single() })).validationProblem())
        assertNotNull(d.copy(output=d.output.copy(endpoint=at.copy(dimensionId="minecraft:the_nether"))).validationProblem())
        assertNotNull(d.copy(output=d.output.copy(slot=64)).validationProblem())
        assertNotNull(d.copy(output=d.output.copy(quantity=2305)).validationProblem())
        assertNotNull(d.copy(travelRadius=Double.NaN).validationProblem())
        assertNotNull(d.copy(noProgressTicks=1001).validationProblem())
    }
    @Test fun commandUsesBoundedExplicitFacesSlotsItemsAndAmounts() {
        val ports=TaskMachineCommands.parsePorts("up,0,minecraft:raw_iron,4;north,0,minecraft:coal,1",at,4)
        assertEquals(listOf(NpcBlockFace.UP,NpcBlockFace.NORTH),ports.map { it.endpoint.side })
        assertEquals(listOf(4,1),ports.map { it.quantity })
        assertFailsWith<IllegalArgumentException> { TaskMachineCommands.parsePorts("up,0,minecraft:raw_iron,4;north,0,minecraft:coal,1",at,1) }
        assertFailsWith<IllegalArgumentException> { TaskMachineCommands.parsePorts("url,0,minecraft:coal,1",at,4) }
        assertFailsWith<IllegalArgumentException> { TaskMachineCommands.parsePorts("a".repeat(2049),at,4) }
    }
    @Test fun actualReceiptDoesNotInferTheMachinesNetSlotContents() {
        val r=HarvestResources(mapOf(ore to 4))
        assertNull(r.observeTransfer(mapOf(ore to 1),ore,3,true))
        assertNull(r.observeTransfer(mapOf(ore to 1,ingot to 2),ingot,2,false))
        assertEquals(3,r.entries[ore]?.delivered);assertEquals(2,r.entries[ingot]?.supplied)
        assertTrue(r.entries.values.all { it.valid() })
        assertEquals(0,r.entries[ingot]?.gathered)
    }
    @Test fun mismatchedAndRepeatedReceiptsFenceWithoutOverwritingCounters() {
        val r=HarvestResources(mapOf(ore to 4))
        assertNull(r.observeTransfer(mapOf(ore to 1),ore,3,true));val before=r.entries
        assertNotNull(r.observeTransfer(mapOf(ore to 1),ore,3,true))
        assertTrue(r.uncertain);assertEquals(before,r.entries)
        assertNotNull(r.observeTransfer(emptyMap(),ore,1,true));assertEquals(before,r.entries)
    }
    @Test fun machineStateCannotInventConfirmedFeedsOrOutputs() {
        val d=definition();val r=HarvestResources(mapOf(ore to 4))
        val s=MachineTaskState(r,intArrayOf(0),listOf(MachinePortShape("minecraft:furnace",1),MachinePortShape("minecraft:furnace",2)))
        assertNull(s.validationProblem(d));s.supplied[0]=1;assertNotNull(s.validationProblem(d));s.supplied[0]=0
        s.collected=1;assertNotNull(s.validationProblem(d));s.collected=0
        s.phase=MachinePhase.RETURN;assertNotNull(s.validationProblem(d))
    }
    @Test fun pollingAndIdlePauseDuringCombatButPrimaryDeadlineContinues() {
        val d=definition();val s=MachineTaskState(HarvestResources(mapOf(ore to 4)),intArrayOf(0),
            listOf(MachinePortShape("minecraft:furnace",1),MachinePortShape("minecraft:furnace",2)),pollRemaining=20,phase=MachinePhase.WORK)
        val frame=TaskFrame(UUID.randomUUID(),d,machine=s)
        val record=TaskRecord(UUID.randomUUID(),UUID.randomUUID(),listOf(frame),emptyList())
        record.advanceTime(10);assertEquals(10,s.pollRemaining);assertEquals(10,s.idleTicks)
        assertNull(record.interrupt(NavigateTaskDefinition(dimension,anchor,budget=TaskBudget(200))))
        record.advanceTime(30);assertEquals(960,frame.remainingTicks);assertEquals(10,s.idleTicks)
        assertTrue(record.pause());record.advanceTime(50);assertEquals(960,frame.remainingTicks)
    }
}
