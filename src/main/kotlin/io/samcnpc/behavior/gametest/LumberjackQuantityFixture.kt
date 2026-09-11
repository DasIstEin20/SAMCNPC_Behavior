package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.task.WorkArea
import io.samcnpc.behavior.task.WorkBox
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/** Explicit irregular terrain fixture shared by GameTest and the real client acceptance run. */
internal class LumberjackQuantityFixture(val origin: BlockPos) {
    fun position(x: Int, y: Int, z: Int): NpcBlockPosition = NpcBlockPosition(origin.x + x, origin.y + y, origin.z + z)
    val container = position(14, 1, 0)
    val area = WorkArea(WorkBox(position(0, 1, 4), position(23, 10, 26)),
        listOf(WorkBox(position(0, 1, 4), position(0, 10, 4))))
    val logs = mutableListOf<NpcBlockPosition>()
    val protected = mutableListOf<Pair<NpcBlockPosition, net.minecraft.world.level.block.Block>>()

    fun prepare(level: ServerLevel) {
        check(logs.isEmpty() && protected.isEmpty()) { "fixture must be prepared only once" }
        for (x in -2..28) for (z in -3..29) {
            val ground = ground(x, z)
            for (y in 0..11) {
                val block = if (y <= ground) Blocks.GRASS_BLOCK else Blocks.AIR
                level.setBlock(origin.offset(x, y, z), block.defaultBlockState(), 3)
            }
        }
        level.setBlock(block(container), Blocks.CHEST.defaultBlockState(), 3)
        val chest = level.getBlockEntity(block(container)) as Container
        chest.setItem(0, ItemStack(Items.IRON_AXE)); chest.setItem(1, ItemStack(Items.IRON_SHOVEL))
        chest.setItem(2, ItemStack(Items.DIRT, 36)); chest.setChanged()
        for (x in listOf(2, 8, 14, 20)) for (z in listOf(6, 12, 18, 24)) {
            for (offset in 1..4) {
                val position = position(x, ground(x, z) + offset, z)
                level.setBlock(block(position), Blocks.OAK_LOG.defaultBlockState(), 3)
                logs.add(position)
            }
        }
        for ((x, z, species) in listOf(Triple(0, 4, Blocks.OAK_LOG), Triple(26, 6, Blocks.OAK_LOG), Triple(5, 9, Blocks.BIRCH_LOG))) {
            for (offset in 1..4) {
                val position = position(x, ground(x, z) + offset, z)
                level.setBlock(block(position), species.defaultBlockState(), 3)
                protected.add(position to species)
            }
        }
        check(logs.size == 64)
    }

    fun verify(level: ServerLevel): Int {
        check(logs.all { level.getBlockState(block(it)).isAir }) { "quantity fixture retains selected logs: ${logs.filterNot { level.getBlockState(block(it)).isAir }}" }
        check(protected.all { (position, species) -> level.getBlockState(block(position)).`is`(species) }) { "quantity fixture lost excluded/outside/wrong-species logs" }
        val chest = level.getBlockEntity(block(container)) as Container
        return (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.OAK_LOG)) chest.getItem(it).count else 0 }
    }

    private fun ground(x: Int, z: Int): Int = if (z < 4) 0 else Math.floorMod(Math.floorDiv(x, 6) + Math.floorDiv(z, 6), 2)
    private fun block(position: NpcBlockPosition) = BlockPos(position.x, position.y, position.z)
}
