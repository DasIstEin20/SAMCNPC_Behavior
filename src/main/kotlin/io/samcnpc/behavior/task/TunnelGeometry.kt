package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition

/** Origin is the left floor-level air cell; descending slices retain a supporting staircase. */
internal data class TunnelGeometry(
    val origin: NpcBlockPosition,
    val direction: TunnelDirection,
    val width: Int,
    val height: Int,
    val length: Int,
    val stepDown: Int = 0,
) {
    val volume: Int get() = if (width in 1..3 && height in 2..4 && length in 1..32) width*height*length else 0

    fun validationProblem(): String? {
        if (volume == 0) return "tunnel dimensions must be width1..3, height2..4, length1..32"
        if (stepDown !in 0..1) return "tunnel stepDown must be 0 or 1"
        if (stepDown == 1 && height < 3) return "descending tunnel requires at least three blocks of headroom"
        val endX = origin.x.toLong()+direction.x*(length-1)-direction.z*(width-1)
        val endZ = origin.z.toLong()+direction.z*(length-1)+direction.x*(width-1)
        if (origin.x.toLong() !in -29_999_984L..29_999_984L || endX !in -29_999_984L..29_999_984L ||
            origin.z.toLong() !in -29_999_984L..29_999_984L || endZ !in -29_999_984L..29_999_984L ||
            origin.y.toLong()-(length-1)*stepDown < -2048L || origin.y.toLong()+height-1 > 2048L)
            return "tunnel coordinates exceed supported world bounds"
        return null
    }

    fun cell(index: Int): NpcBlockPosition {
        require(validationProblem() == null && index in 0 until volume)
        val cross = width*height
        val step = index/cross
        val lateral = index%cross/height
        val vertical = height-1-index%height
        return NpcBlockPosition(origin.x+direction.x*step-direction.z*lateral,
            origin.y-step*stepDown+vertical, origin.z+direction.z*step+direction.x*lateral)
    }

    fun bounds(): WorkBox {
        val end = cell(volume-1)
        return WorkBox(NpcBlockPosition(minOf(origin.x,end.x),end.y,minOf(origin.z,end.z)),
            NpcBlockPosition(maxOf(origin.x,end.x),origin.y+height-1,maxOf(origin.z,end.z)))
    }

    /** The enclosing box includes ceiling/support cells that are never removal permission. */
    fun contains(position: NpcBlockPosition): Boolean {
        val dx = position.x.toLong()-origin.x
        val dz = position.z.toLong()-origin.z
        val step = dx*direction.x+dz*direction.z
        val lateral = -dx*direction.z+dz*direction.x
        val vertical = position.y.toLong()-origin.y+step*stepDown
        return step in 0L until length.toLong() && lateral in 0L until width.toLong() && vertical in 0L until height.toLong()
    }
}
