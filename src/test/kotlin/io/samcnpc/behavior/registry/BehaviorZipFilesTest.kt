package io.samcnpc.behavior.registry

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BehaviorZipFilesTest {
    @TempDir lateinit var root: Path
    private fun archive(entries: List<Pair<String, ByteArray>>, name: String = "custom.zip"): Path {
        val folder = root.resolve("resources/samcnpc/behaviors")
        Files.createDirectories(folder)
        val path = folder.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((entry, body) in entries) { out.putNextEntry(ZipEntry(entry)); out.write(body); out.closeEntry() }
        }
        return path
    }
    private fun pack(id: String = "test:zip") = """{"schemaVersion":1,"id":"$id","description":"ZIP test","priority":0,"channels":["look"],"rules":[{"id":"look","priority":0,"when":{"test":{"condition":"samcnpc:always"}},"actions":[{"action":"samcnpc:look_at_summoner"}]}]}""".toByteArray()
    private fun read() = BehaviorZipFiles.readExternal(root)

    @Test fun singleAndMultipleDocumentsHaveStableSourceIdentityAndIgnoreUnrelatedJson() {
        archive(listOf("behaviors/z.json" to pack(), "README.md" to "instructions".toByteArray(), "metadata.json" to "not behavior".toByteArray()))
        assertEquals(listOf("external-zip:custom.zip!/behaviors/z.json"), read().map { it.first })
        archive(listOf("behaviors/nested/z.json" to pack("test:z"), "behaviors/a.JSON" to pack("test:a")))
        val result = read()
        assertEquals(listOf("external-zip:custom.zip!/behaviors/a.JSON", "external-zip:custom.zip!/behaviors/nested/z.json"), result.map { it.first })
        for ((source, body) in result) assertNotNull(BehaviorDefinitions.compiler.compile(source, body).pack)
    }
    @Test fun looseJsonAndZipCoexistWithoutRewritingEither() {
        val config = Files.createDirectory(root.resolve("config"))
        BehaviorPackFiles.readExternal(config)
        Files.write(config.resolve("samcnpc/behaviors/loose.json"), pack("test:loose"))
        archive(listOf("behaviors/a.json" to pack()))
        assertEquals(2, (BehaviorPackFiles.readExternal(config) + read()).size)
        assertTrue(Files.exists(config.resolve("samcnpc/behaviors/loose.json")))
    }
    @Test fun malformedDocumentAndUnknownActionReachTheSameCompilerWithArchiveIdentity() {
        for (body in listOf("{".toByteArray(), String(pack()).replace("samcnpc:look_at_summoner", "test:execute_code").toByteArray())) {
            archive(listOf("behaviors/bad.json" to body))
            val (source, text) = read().single()
            val result = BehaviorDefinitions.compiler.compile(source, text)
            assertNull(result.pack)
            assertTrue(result.report.messages.any { it.startsWith("external-zip:custom.zip!/behaviors/bad.json:") })
        }
    }
    @Test fun traversalAbsoluteWindowsControlAndAmbiguousPathsAreRejected() {
        for (name in listOf("../evil.json", "/behaviors/a.json", "C:/a.json", "behaviors/../a.json", "behaviors/./a.json", "behaviors//a.json", "behaviors\\a.json", "behaviors/a\u0001.json", "behaviors/space /a.json", "behaviors/" + "nested/".repeat(8) + "a.json")) {
            archive(listOf(name to pack()))
            assertFailsWith<IOException>(name) { read() }
        }
    }
    @Test fun archiveEntryExpandedCountAndCompressionBoundsAreEnforced() {
        archive(listOf("behaviors/a.json" to ByteArray(BoundedBehaviorJson.MAX_BYTES + 1)))
        assertFailsWith<IOException> { read() }
        archive((0..BehaviorZipFiles.MAX_ENTRIES).map { "docs/$it.txt" to byteArrayOf(1) })
        assertFailsWith<IOException> { read() }
        val random = java.util.Random(17)
        archive((0..16).map { "docs/$it.txt" to ByteArray(BoundedBehaviorJson.MAX_BYTES).also(random::nextBytes) })
        assertContains(assertFailsWith<IOException> { read() }.message.orEmpty(), "total decompressed")
        archive(listOf("behaviors/a.json" to ByteArray(120000) { 32 }))
        assertContains(assertFailsWith<IOException> { read() }.message.orEmpty(), "compression ratio")
        val file = archive(listOf("behaviors/a.json" to pack()))
        Files.write(file, ByteArray(BehaviorZipFiles.MAX_ARCHIVE_BYTES + 1))
        assertFailsWith<IOException> { read() }
    }
    @Test fun totalArchiveAndDocumentCountsAreBounded() {
        for (i in 0..BehaviorZipFiles.MAX_ARCHIVES) archive(listOf("behaviors/a.json" to pack("test:p$i")), "$i.zip")
        assertContains(assertFailsWith<IOException> { read() }.message.orEmpty(), "more than 16")
    }
    @Test fun expandedBudgetAcrossArchivesIncludesIgnoredDocuments() {
        val random = java.util.Random(23)
        for (i in 0..4) archive(listOf("behaviors/a.json" to pack("test:p$i")) +
            (0..14).map { "docs/$it.txt" to ByteArray(BoundedBehaviorJson.MAX_BYTES).also(random::nextBytes) }, "$i.zip")
        assertContains(assertFailsWith<IOException> { read() }.message.orEmpty(), "all ZIPs exceed")
    }
    @Test fun documentCountCannotBypassLoosePackBound() {
        archive((0..64).map { "behaviors/$it.json" to pack("test:p$it") })
        assertFailsWith<IOException> { read() }
    }
    @Test fun invalidZipTruncationDuplicatePathsAndEmptyArchivesAreRejected() {
        var file = archive(listOf("behaviors/a.json" to pack()))
        val original = Files.readAllBytes(file)
        for (bytes in listOf(byteArrayOf(), "not a ZIP".toByteArray(), original.copyOf(original.size - 10))) {
            Files.write(file, bytes); assertFailsWith<IOException> { read() }
        }
        archive(listOf("behaviors/a.json" to pack(), "behaviors/A.json" to pack("test:other")))
        assertFailsWith<IOException> { read() }
        file = archive(listOf("metadata.json" to "{}".toByteArray()))
        assertFailsWith<IOException> { read() }
        assertTrue(Files.isRegularFile(file))
    }
    @Test fun symlinkMetadataAndMismatchedCentralNamesCannotBypassValidation() {
        for (symlink in listOf(true, false)) {
            val file = archive(listOf("behaviors/a.json" to pack()))
            val bytes = Files.readAllBytes(file)
            val at = (0..bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
            if (symlink) { bytes[at + 5] = 3; bytes[at + 41] = 0xa1.toByte() }
            else bytes[at + 46 + "behaviors/".length] = 'z'.code.toByte()
            Files.write(file, bytes)
            assertFailsWith<IOException> { read() }
        }
    }
    @Test fun replacingArchiveDuringReadRejectsCandidate() {
        val file = archive(listOf("behaviors/a.json" to pack()))
        assertContains(assertFailsWith<IOException> {
            BehaviorZipFiles.readStable(file, file.parent) { Files.write(file, byteArrayOf(1, 2, 3)) }
        }.message.orEmpty(), "changed during reload")
    }
}
