package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.task.WorkArea
import io.samcnpc.behavior.task.WorkBox
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SaplingBlock

/** Vanilla sapling growth supplies a real bent 2x2 tree, shared with the client proof. */
internal class DarkOakWorkFixture(val origin: BlockPos, val seed: Long) {
    fun position(x: Int, y: Int, z: Int) = NpcBlockPosition(origin.x + x, origin.y + y, origin.z + z)
    val container = position(2, 1, 2)
    val area = WorkArea(WorkBox(position(0, 1, 0), position(24, 20, 24)))
    val logs = mutableListOf<BlockPos>()
    private val protected = mutableListOf<Pair<BlockPos, net.minecraft.world.level.block.Block>>()
    val height: Int get() = logs.maxOf { it.y - origin.y }
    val columns: Int get() = logs.map { it.x to it.z }.distinct().size

    fun prepare(level: ServerLevel) {
        check(logs.isEmpty() && protected.isEmpty())
        for (x in -2..26) for (z in -2..26) for (y in 0..21) {
            level.setBlock(origin.offset(x, y, z), (if (y == 0) Blocks.GRASS_BLOCK else Blocks.AIR).defaultBlockState(), 3)
        }
        val sapling = Blocks.DARK_OAK_SAPLING as SaplingBlock
        val mature = sapling.defaultBlockState().setValue(SaplingBlock.STAGE, 1)
        for (x in 12..13) for (z in 12..13) level.setBlockAndUpdate(origin.offset(x, 1, z), mature)
        sapling.advanceTree(level, origin.offset(12, 1, 12), mature, RandomSource.create(seed))
        for (x in 0..24) for (z in 0..24) for (y in 1..20) {
            val pos = origin.offset(x, y, z)
            if (level.getBlockState(pos).`is`(Blocks.DARK_OAK_LOG)) logs.add(pos)
        }
        check(logs.size >= 24 && columns >= 4) { "native dark oak did not generate: seed=$seed logs=${logs.size} columns=$columns" }
        check((12..13).all { x -> (12..13).all { z -> origin.offset(x, 1, z) in logs } }) { "native tree lost its 2x2 base" }
        for ((x, species) in listOf(5 to Blocks.OAK_LOG, 20 to Blocks.BIRCH_LOG)) for (y in 1..4) {
            val pos = origin.offset(x, y, 5)
            check(level.getBlockState(pos).isAir)
            level.setBlockAndUpdate(pos, species.defaultBlockState())
            protected.add(pos to species)
        }
        level.setBlockAndUpdate(BlockPos(container.x, container.y, container.z), Blocks.CHEST.defaultBlockState())
        val chest = chest(level)
        chest.setItem(0, ItemStack(Items.DIAMOND_AXE))
        chest.setItem(1, ItemStack(Items.IRON_SHOVEL))
        // This fixture requires complete removal of unchanged supports. Dirt can legitimately
        // turn into grass during long natural-tree work; its strict identity/report contract
        // has a separate real grass-spread/reload test in ResumedPillarGameTests.
        // Coarse dirt keeps the same hardness, shovel use and recoverable material budget.
        chest.setItem(2, ItemStack(Items.COARSE_DIRT, 24))
        chest.setChanged()
    }

    fun chest(level: ServerLevel) = level.getBlockEntity(BlockPos(container.x, container.y, container.z)) as Container

    fun verify(level: ServerLevel): Int {
        val left = logs.filterNot { level.getBlockState(it).isAir }
        check(left.isEmpty()) { "native dark oak left ${left.size}/${logs.size} logs: $left" }
        check(protected.all { (pos, species) -> level.getBlockState(pos).`is`(species) }) { "dark oak task cut a different species" }
        val chest = chest(level)
        return (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.DARK_OAK_LOG)) chest.getItem(it).count else 0 }
    }
}
