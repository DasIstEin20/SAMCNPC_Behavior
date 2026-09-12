package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class TaskNavigationStepTest {
    private val destination=NpcPosition(4.0,64.0,0.0)
    private val bounds=NpcNavigationBounds(NpcPosition(-8.0,60.0,-8.0),NpcPosition(8.0,70.0,8.0))
    private val definition=NavigateTaskDefinition("minecraft:overworld",destination)
    private val npc=Body()
    private val world=World()
    private val record=TaskRecord.start(npc.npcUuid,definition,emptyList())
    private val execution=TaskExecution(record.id,record.primary.id)

    @Test fun missingStandingSpaceIsAnExplicitLegFailureWithoutSpendingTaskRetryBudget() {
        world.available=false
        val result=assertIs<TaskNavigationStep.Failed>(TaskNavigator.step(record,execution,npc,world,definition,bounds))
        assertEquals(TaskReason.DESTINATION_UNAVAILABLE,result.reason)
        assertEquals(0,record.primary.failures);assertEquals(6000,record.primary.remainingTicks)
        assertEquals(TaskStatus.RUNNING,record.status);assertNull(npc.last)
        TaskNavigator.move(record,execution,npc,world,definition,bounds)
        assertEquals(1,record.primary.failures);assertEquals(TaskStatus.WAITING,record.status)
        assertEquals(6000,record.primary.remainingTicks)
    }
    @Test fun aMechanicalSuccessOutsideTheGoalCannotCompleteTheBehaviorLeg() {
        npc.result=NpcActionResult.succeeded("mechanical envelope")
        val result=TaskNavigator.move(record,execution,npc,world,definition,bounds)
        assertEquals(NpcActionStatus.RUNNING,result.status)
        assertEquals(TaskStatus.WAITING,record.status)
        assertEquals(TaskReason.NO_PROGRESS,record.reason)
        assertEquals(bounds,assertNotNull(npc.last).bounds)
        assertEquals(destination,assertNotNull(npc.last).position)
    }
    @Test fun onlyFreshGroundedArrivalCompletesTheLegWithoutSubmittingAnotherPath() {
        npc.observation=npc.observation.copy(position=destination,onGround=false)
        assertIs<TaskNavigationStep.Progress>(TaskNavigator.step(record,execution,npc,world,definition,bounds))
        npc.last=null
        npc.observation=npc.observation.copy(onGround=true)
        assertEquals(TaskNavigationStep.Arrived,TaskNavigator.step(record,execution,npc,world,definition,bounds))
        assertNull(npc.last);assertEquals(0,record.primary.failures)
    }
    @Test fun anOldCompletionOutsideTheGoalIsNotReplayedAsAnotherNavigationSubmission() {
        execution.completion=NpcActionResult.succeeded("old route",UUID(1,2))
        val result=assertIs<TaskNavigationStep.Failed>(TaskNavigator.step(record,execution,npc,world,definition,bounds))
        assertEquals(TaskReason.NO_PROGRESS,result.reason)
        assertNull(execution.completion);assertNull(npc.last)
        assertEquals(TaskStatus.RUNNING,record.status);assertEquals(0,record.primary.failures)
    }
    @Test fun explicitStopDetachesSynchronousCancellationBeforeResumingTheSameDestination() {
        assertIs<TaskNavigationStep.Progress>(TaskNavigator.step(record,execution,npc,world,definition,bounds))
        val previous = assertNotNull(execution.navigationId)
        execution.approach = definition.destination
        npc.onStop = {
            // Core emits completion inside stopControl, before it returns to Behavior.
            if (execution.navigationId == previous)
                execution.completion = NpcActionResult.failed("navigation stopped",NpcActionCode.CANCELLED,previous)
        }
        TaskNavigator.stop(execution,npc)
        assertNull(execution.navigationId); assertNull(execution.completion); assertNull(execution.approach)
        npc.result = NpcActionResult.accepted("new leg",UUID(1,2))
        assertIs<TaskNavigationStep.Progress>(TaskNavigator.step(record,execution,npc,world,definition,bounds))
        assertEquals(UUID(1,2),execution.navigationId)
        assertEquals(0,record.totalFailures); assertEquals(6000,record.primary.remainingTicks)
    }
    private class Body : TestNpcFacade() {
        var onStop: () -> Unit = {}
        override fun stopControl(): NpcActionResult { onStop(); return NpcActionResult.succeeded("stopped") }
        var observation=decisionContext().snapshot
        var last: NpcNavigationRequest?=null
        var result=NpcActionResult.accepted("submitted",UUID(1,1))
        override fun snapshot()=observation
        override fun navigateTo(request: NpcNavigationRequest): NpcActionResult { last=request;return result }
    }
    private class World : NpcWorldView {
        var available=true
        override val dimensionId="minecraft:overworld"
        override fun observeStandingSpace(feet: NpcPosition)=if (available) NpcStandingSpaceObservation(feet,true,true,false) else null
        override fun observeEntity(uuid: UUID): NpcEntityObservation?=error("unexpected entity read")
        override fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation> = error("bounded step used direct-control recovery")
        override fun observeBlock(position: NpcBlockPosition): NpcBlockObservation?=error("unexpected block read")
        override fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation?=error("unexpected container read")
        override fun raycast(request: NpcRaycastRequest): NpcRaycastResult=error("unexpected raycast")
    }
}
