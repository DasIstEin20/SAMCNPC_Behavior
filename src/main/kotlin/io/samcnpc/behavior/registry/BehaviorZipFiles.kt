package io.samcnpc.behavior.registry

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipInputStream

/** External SAMCNPC resources, not vanilla resource packs. No archive member is ever extracted. */
internal object BehaviorZipFiles {
    const val MAX_ARCHIVES = 16
    const val MAX_ENTRIES = 128
    const val MAX_ARCHIVE_BYTES = 4 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 2 * 1024 * 1024
    const val MAX_TOTAL_EXPANDED_BYTES = 8 * 1024 * 1024
    const val MAX_COMPRESSION_RATIO = 200

    fun readExternal(instanceRoot: Path): List<Pair<String, String>> = readBundleDocuments(instanceRoot).filter { it.first.substringAfter("!/").startsWith("behaviors/") }

    internal fun readBundleDocuments(instanceRoot: Path): List<Pair<String, String>> {
        val root = instanceRoot.toRealPath()
        val resources = BehaviorPackFiles.prepareDirectory(root.resolve("resources"))
        val namespace = BehaviorPackFiles.prepareDirectory(resources.resolve("samcnpc"))
        val directory = BehaviorPackFiles.prepareDirectory(namespace.resolve("behaviors"))
        val entries = try { Files.list(directory).use { it.limit(1025).toList() } }
            catch (error: java.io.UncheckedIOException) { throw IOException("cannot enumerate ZIP directory: ${error.message}", error) }
        if (entries.size > 1024) throw IOException("ZIP directory exceeds 1024 entries")
        val archives = entries.filter { it.fileName.toString().endsWith(".zip", true) }.sorted()
        if (archives.size > MAX_ARCHIVES) throw IOException("more than $MAX_ARCHIVES ZIP files")
        val result = ArrayList<Pair<String, String>>()
        var expanded = 0L
        for (archive in archives) {
            val source = "external-zip:${archive.fileName}"
            try {
                val bytes = readStable(archive, directory)
                val index = BehaviorZipIndex.read(bytes)
                expanded += index.sumOf { it.size }
                if (expanded > MAX_TOTAL_EXPANDED_BYTES) throw IOException("all ZIPs exceed $MAX_TOTAL_EXPANDED_BYTES decompressed bytes")
                val documents = readDocuments(bytes, index, source)
                if (documents.none { it.first.substringAfter("!/").startsWith("behaviors/") }) throw IOException("no behavior JSON documents below behaviors/")
                io.samcnpc.behavior.mission.MissionBundle.validate(documents)
                result.addAll(documents)
                if (result.count { it.first.substringAfter("!/").startsWith("behaviors/") } > BehaviorPackFiles.MAX_PACKS) throw IOException("more than ${BehaviorPackFiles.MAX_PACKS} ZIP behavior documents")
                if (result.size > BehaviorPackFiles.MAX_PACKS + 32 + MAX_ARCHIVES) throw IOException("too many ZIP documents")
            } catch (error: IOException) { throw IOException("$source: ${error.message}", error) }
            catch (error: com.google.gson.JsonParseException) { throw IOException("$source: invalid mission manifest JSON", error) }
            catch (error: IllegalArgumentException) { throw IOException("$source: invalid ZIP metadata/encoding", error) }
        }
        return result
    }

    internal fun readStable(path: Path, directory: Path, afterRead: () -> Unit = {}): ByteArray {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile || path.toRealPath().parent != directory || before.size() > MAX_ARCHIVE_BYTES) throw IOException("only regular ZIP files up to $MAX_ARCHIVE_BYTES bytes are accepted")
        val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { it.readNBytes(MAX_ARCHIVE_BYTES + 1) }
        if (bytes.size > MAX_ARCHIVE_BYTES) throw IOException("ZIP grew beyond compressed size bound")
        afterRead()
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
            before.fileKey() != after.fileKey() || path.toRealPath().parent != directory) throw IOException("archive changed during reload; finish saving and reload again")
        return bytes
    }

    private fun readDocuments(bytes: ByteArray, index: List<BehaviorZipIndex.Entry>, source: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            for (metadata in index) {
                val entry = zip.nextEntry ?: throw IOException("ZIP local entries differ from directory")
                if (entry.name != metadata.name || entry.method != metadata.method) throw IOException("ZIP local path/method differs from directory")
                val data = zip.readNBytes(BoundedBehaviorJson.MAX_BYTES + 1)
                if (data.size > BoundedBehaviorJson.MAX_BYTES) throw IOException("${entry.name}: oversized decompressed entry")
                zip.closeEntry()
                if (entry.size != data.size.toLong() || metadata.size != entry.size || metadata.compressed != entry.compressedSize) throw IOException("${entry.name}: ZIP sizes differ from directory")
                if (!entry.isDirectory && entry.name.endsWith(".json", true) &&
                    (entry.name.startsWith("behaviors/") || entry.name.startsWith("missions/") || entry.name == "mission-manifest.json")) {
                    result.add("$source!/${entry.name}" to BoundedBehaviorJson.read(ByteArrayInputStream(data)))
                }
            }
            if (zip.nextEntry != null) throw IOException("unlisted ZIP local entry")
        }
        return result.sortedBy { it.first }
    }
}
