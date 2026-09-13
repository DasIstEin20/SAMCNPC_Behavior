package io.samcnpc.behavior.registry

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BehaviorPackFilesTest {
    @TempDir lateinit var root: Path

    @Test
    fun `external files are bounded and returned in deterministic filename order`() {
        assertTrue(BehaviorPackFiles.readExternal(root).isEmpty())
        val directory = root.resolve("samcnpc/behaviors")
        Files.writeString(directory.resolve("z.json"), "{}")
        Files.writeString(directory.resolve("a.JSON"), "[]")
        Files.writeString(directory.resolve("ignored.json.tmp"), "unfinished")
        assertEquals(listOf("external:a.JSON" to "[]", "external:z.json" to "{}"), BehaviorPackFiles.readExternal(root))
        for (index in 2 until BehaviorPackFiles.MAX_PACKS) Files.writeString(directory.resolve("pack_$index.json"), "{}")
        assertEquals(BehaviorPackFiles.MAX_PACKS, BehaviorPackFiles.readExternal(root).size)
        Files.writeString(directory.resolve("excess.json"), "{}")
        val error = assertFailsWith<IOException> { BehaviorPackFiles.readExternal(root) }
        assertTrue("more than 64" in error.message.orEmpty())
        Files.delete(directory.resolve("excess.json"))
        // 64 JSON files plus the already present ignored file, then 959 more entries.
        repeat(959) { index -> Files.createFile(directory.resolve("unfinished_" + index + ".tmp")) }
        assertEquals(64, BehaviorPackFiles.readExternal(root).size)
        Files.createFile(directory.resolve("one_more.tmp"))
        assertTrue("exceeds 1024 entries" in assertFailsWith<IOException> { BehaviorPackFiles.readExternal(root) }.message.orEmpty())
    }

    @Test
    fun `bad encoding oversized input and non regular JSON entries reject the candidate`() {
        BehaviorPackFiles.readExternal(root)
        val file = root.resolve("samcnpc/behaviors/bad.json")
        Files.write(file, byteArrayOf(0xc3.toByte()))
        assertTrue(assertFailsWith<IOException> { BehaviorPackFiles.readExternal(root) }.message.orEmpty().contains("bad.json"))
        Files.write(file, ByteArray(BoundedBehaviorJson.MAX_BYTES + 1) { 32 })
        assertTrue(assertFailsWith<IOException> { BehaviorPackFiles.readExternal(root) }.message.orEmpty().contains("exceeds"))
        Files.delete(file)
        Files.createDirectory(file)
        assertTrue(assertFailsWith<IOException> { BehaviorPackFiles.readExternal(root) }.message.orEmpty().contains("regular JSON"))
    }

    @Test
    fun `an unavailable behavior directory does not get replaced or silently ignored`() {
        Files.writeString(root.resolve("samcnpc"), "existing user data")
        assertFailsWith<IOException> { BehaviorPackFiles.readExternal(root) }
        assertEquals("existing user data", Files.readString(root.resolve("samcnpc")))
    }
}
