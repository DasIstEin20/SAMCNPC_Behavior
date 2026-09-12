package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.navigation.ContainerApproachKernel
import io.samcnpc.behavior.kernel.navigation.WorkApproachVisibility
import io.samcnpc.core.api.*

/** Supported movement toward a supplied cell, followed by fresh visibility inside real use reach. */
internal object WorkInteractionApproach {
    fun move(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: LocalWorkDefinition,target: NpcBlockPosition): NpcActionResult? {
        val snapshot=npc.snapshot()
        val center=TransportTaskDefinition.center(target)
        if (snapshot.onGround && TaskNavigator.distanceSquared(snapshot.eyePosition,center) <= 3.5*3.5 && world.visibleBlockFrom(snapshot.position,target) == true) {
            TaskInventory.resetRoute(e,npc)
            return null
        }
        if (e.approach == null) {
            if (e.rejectedWorkStances.size >= 16) return NpcActionResult.failed("sixteen supported work approaches lacked interaction visibility")
            if (!ContainerApproachKernel.admitSelection(world,2)) return NpcActionResult.running("work interaction approach queued")
            e.approach=ContainerApproachKernel.select(world,target,snapshot.position,offset=2,allowed={ cell ->
                val feet=NpcPosition(cell.x+0.5,cell.y.toDouble(),cell.z+0.5)
                d.contains(feet) && cell !in e.rejectedWorkStances && WorkApproachVisibility.allowsCandidate(world,snapshot.position,feet,target)
            }) ?: return NpcActionResult.failed("supplied work cell has no supported visible approach")
        }
        val feet=checkNotNull(e.approach)
        val result=TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,feet,arrivalDistance=0.3,budget=d.budget))
        if (result.status == NpcActionStatus.SUCCEEDED) {
            e.rejectedWorkStances.add(FoodTaskDefinition.cell(feet)); TaskInventory.resetRoute(e,npc)
            return NpcActionResult.running("approach reached; rechecking actual use visibility")
        }
        return result
    }
}
