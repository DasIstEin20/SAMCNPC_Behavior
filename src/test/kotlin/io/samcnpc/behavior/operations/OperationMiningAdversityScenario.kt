package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

internal enum class OperationMiningAdversityKind {
    FALLING_BLOCK, UNBREAKABLE, LOST_TOOL, NO_SUPPORT, FULL_INVENTORY, DESTROYED_DESTINATION,
}

/** Real hazardous geometry, exhausted durability and lost storage must preserve physical partial results. */
internal class OperationMiningAdversityScenario(server: MinecraftServer, val kind: OperationMiningAdversityKind) {
    val scene = OperationScene.create(server.overworld(), OperationKind.MINING, BlockPos(2300 + kind.ordinal * 64, 80, 1000))
    private var stage = 0
    private var ticks = 0
    private var quietTicks = 0
    private var destinationRemoved = false
    private val initialRaw = if (kind == OperationMiningAdversityKind.FULL_INVENTORY) 64 else 5
    var complete = false
        private set

    fun tick() {
        if (complete) return
        check(++ticks < 1200) { "$kind mining adversity timed out stage=$stage" }
        if (!scene.loaded || !scene.body.onGround()) return
        if (stage == 0) {
            scene.makeChest(0, 7)
            val tool = ItemStack(Items.IRON_PICKAXE)
            if (kind == OperationMiningAdversityKind.LOST_TOOL) tool.damageValue = tool.maxDamage - 1
            scene.give(tool)
            scene.give(Items.RAW_IRON, initialRaw)
            if (kind == OperationMiningAdversityKind.FULL_INVENTORY) scene.give(Items.STONE, 34 * 64)
            val block = if (kind == OperationMiningAdversityKind.UNBREAKABLE) Blocks.BEDROCK else Blocks.IRON_ORE
            for (x in 12..13) {
                scene.level.setBlock(scene.pos(x, 1, 0), block.defaultBlockState(), 3)
                if (kind == OperationMiningAdversityKind.FALLING_BLOCK) scene.level.setBlock(scene.pos(x, 2, 0), Blocks.SAND.defaultBlockState(), 3)
            }
            if (kind == OperationMiningAdversityKind.NO_SUPPORT) {
                for (x in 8..18) for (z in -5..5) scene.level.setBlock(scene.pos(x, 0, z), Blocks.AIR.defaultBlockState(), 3)
            }
            val resource = if (kind == OperationMiningAdversityKind.UNBREAKABLE) "minecraft:bedrock" else "minecraft:iron_ore"
            scene.assign(MiningTaskDefinition(scene.npc.snapshot().dimensionId,
                MiningWorkOrder(scene.area(12, 13), MiningMethod.EXPOSED, WorkResourceIds(listOf(resource))),
                WorkResourceIds(listOf("minecraft:raw_iron")), scene.choices(0, 7), 2, MiningCounting.DELIVERED_ITEMS,
                scene.start, returnTo = scene.start, budget = TaskBudget(1000)))
            stage = 1
            return
        }
        val record = scene.record
        val state = checkNotNull(record.primary.mining)
        val definition = record.primary.definition as MiningTaskDefinition
        if (kind == OperationMiningAdversityKind.DESTROYED_DESTINATION && !destinationRemoved &&
            state.phase == MiningPhase.DEPOSIT && state.selectedContainer != null) {
            check(state.selectedContainer == scene.block(0, 1, 7) && state.cargo(definition) == 2 && state.delivered(definition) == 0)
            check(scene.count(0, 7, Items.RAW_IRON) == 0)
            scene.level.setBlock(scene.pos(0, 1, 7), Blocks.AIR.defaultBlockState(), 3)
            destinationRemoved = true
        }
        if (!record.status.terminal) return
        check(record.status == TaskStatus.FAILED && record.primary.remainingTicks > 0) { "$kind ${record.report()}" }
        check(record.reason == if (kind == OperationMiningAdversityKind.DESTROYED_DESTINATION) TaskReason.RETRY_LIMIT else TaskReason.WORK_FAILED) { record.report() }
        val removed = when (kind) {
            OperationMiningAdversityKind.LOST_TOOL -> 1
            OperationMiningAdversityKind.DESTROYED_DESTINATION -> 2
            else -> 0
        }
        check(state.selection.removed.size == removed)
        check((12..13).count { scene.level.getBlockState(scene.pos(it, 1, 0)).isAir } == removed)
        check(!state.resources.physical.uncertain && state.resources.physical.entries.values.all { it.valid() })
        val delivered = if (kind == OperationMiningAdversityKind.LOST_TOOL) 1 else 0
        check(state.delivered(definition) == delivered)
        if (!destinationRemoved) check(scene.count(0, 7, Items.RAW_IRON) == delivered)
        check(scene.carried("minecraft:raw_iron") == initialRaw + removed - delivered)
        check(scene.carried("minecraft:iron_pickaxe") == if (kind == OperationMiningAdversityKind.LOST_TOOL) 0 else 1)
        when (kind) {
            OperationMiningAdversityKind.FALLING_BLOCK -> {
                check(state.selection.problems.getOrDefault(MiningProblem.FALLING_BLOCK, 0) == 2)
                check((12..13).all { scene.level.getBlockState(scene.pos(it, 2, 0)).`is`(Blocks.SAND) })
            }
            OperationMiningAdversityKind.UNBREAKABLE -> check(state.selection.problems.getOrDefault(MiningProblem.UNBREAKABLE, 0) == 2)
            OperationMiningAdversityKind.LOST_TOOL -> check(state.stop == MiningProblem.MISSING_TOOL)
            OperationMiningAdversityKind.NO_SUPPORT -> check(state.stop == MiningProblem.UNREACHABLE)
            OperationMiningAdversityKind.FULL_INVENTORY -> {
                check(state.stop == MiningProblem.INVENTORY_FULL)
                check(scene.carried("minecraft:stone") == 34 * 64)
            }
            OperationMiningAdversityKind.DESTROYED_DESTINATION -> {
                check(destinationRemoved && scene.level.getBlockState(scene.pos(0, 1, 7)).isAir)
                check(record.totalFailures == definition.budget.attempts && record.detail.contains("STORAGE_FULL"))
            }
        }
        if (kind == OperationMiningAdversityKind.FALLING_BLOCK || kind == OperationMiningAdversityKind.UNBREAKABLE) {
            val expected = "${kind.name}=2"
            check(record.detail.contains(expected)) { "Actual refusal cause missing from final report: ${record.detail}" }
            check(TaskService.status(scene.server, scene.npcId).orEmpty().contains(expected)) { "Manual status hides the actual skipped blocks" }
        }
        scene.requireReleased()
        if (!destinationRemoved) scene.requireReturned()
        if (++quietTicks < 20) return
        scene.close()
        complete = true
    }
}
