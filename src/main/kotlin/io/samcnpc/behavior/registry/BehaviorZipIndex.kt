package io.samcnpc.behavior.registry

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reads only bounded ZIP metadata; the JDK inflater still owns decompression and CRC validation. */
internal object BehaviorZipIndex {
    data class Entry(val name: String, val size: Long, val compressed: Long, val method: Int, val offset: Long)

    fun read(bytes: ByteArray): List<Entry> {
        fun u16(p: Int): Int {
            if (p < 0 || p > bytes.size - 2) throw IOException("truncated ZIP directory")
            return (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8)
        }
        fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
        var end = bytes.size - 22
        while (end >= maxOf(0, bytes.size - 65557)) {
            if (u32(end) == 0x06054b50L && end + 22 + u16(end + 20) == bytes.size) break
            end--
        }
        if (end < maxOf(0, bytes.size - 65557)) throw IOException("invalid ZIP: missing end directory")
        val count = u16(end + 10)
        if (u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != count || count !in 1..BehaviorZipFiles.MAX_ENTRIES) {
            throw IOException("ZIP must contain 1..${BehaviorZipFiles.MAX_ENTRIES} entries on one disk; ZIP64 is unsupported")
        }
        val directorySize = u32(end + 12)
        val directoryStart = u32(end + 16)
        if (directoryStart + directorySize != end.toLong()) throw IOException("invalid ZIP directory bounds; ZIP64 is unsupported")
        var cursor = directoryStart.toInt()
        val entries = ArrayList<Entry>(count)
        val names = HashSet<String>()
        repeat(count) {
            if (cursor > end - 46 || u32(cursor) != 0x02014b50L) throw IOException("invalid ZIP central entry")
            val flags = u16(cursor + 8)
            val method = u16(cursor + 10)
            val nameSize = u16(cursor + 28)
            val next = cursor + 46 + nameSize + u16(cursor + 30) + u16(cursor + 32)
            if (next > end || nameSize !in 1..720 || flags and 1 != 0 || method !in listOf(0, 8) || u16(cursor + 34) != 0) {
                throw IOException("unsupported/encrypted or malformed ZIP entry")
            }
            val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, cursor + 46, nameSize)).toString()
            validateName(name)
            if (!names.add(name.lowercase(java.util.Locale.ROOT))) throw IOException("duplicate/ambiguous ZIP path: $name")
            val unixType = (u32(cursor + 38) ushr 16).toInt() and 0xf000
            if (unixType != 0 && unixType != 0x8000 && unixType != 0x4000) throw IOException("links/special files are forbidden in ZIP: $name")
            if (unixType == 0x4000 && !name.endsWith('/')) throw IOException("inconsistent ZIP directory: $name")
            val size = u32(cursor + 24)
            val compressed = u32(cursor + 20)
            val offset = u32(cursor + 42)
            if (size > BoundedBehaviorJson.MAX_BYTES || compressed > BehaviorZipFiles.MAX_ARCHIVE_BYTES || offset >= directoryStart) {
                throw IOException("ZIP entry exceeds bounds: $name")
            }
            if (size > maxOf(1L, compressed) * BehaviorZipFiles.MAX_COMPRESSION_RATIO) throw IOException("pathological compression ratio: $name")
            if (name.endsWith('/') && size != 0L) throw IOException("ZIP directory contains data: $name")
            entries.add(Entry(name, size, compressed, method, offset))
            cursor = next
        }
        if (cursor != end || entries.sumOf { it.size } > BehaviorZipFiles.MAX_EXPANDED_BYTES) throw IOException("ZIP exceeds total decompressed bound or has trailing directory data")
        val ordered = entries.sortedBy { it.offset }
        if (ordered.first().offset != 0L || ordered.map { it.offset }.distinct().size != ordered.size) throw IOException("ZIP preambles/overlapping entries are unsupported")
        return ordered
    }

    private fun validateName(name: String) {
        val parts = name.removeSuffix("/").split('/')
        if (name.length > 240 || name.startsWith('/') || '\\' in name || ':' in name ||
            name.any { it.code < 32 || it.code == 127 } || parts.size > 8 || parts.any { it.isEmpty() || it == "." || it == ".." || it.endsWith(' ') || it.endsWith('.') }) {
            throw IOException("unsafe or ambiguous ZIP path: ${name.take(240)}")
        }
    }
}
