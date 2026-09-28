package io.samcnpc.behavior.registry

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Bounded external discovery and reads; no symlink/junction redirects outside server config. */
internal object BehaviorPackFiles {
    const val MAX_PACKS = 64
    private const val MAX_DIRECTORY_ENTRIES = 1024

    fun readExternal(configRoot: Path): List<Pair<String, String>> {
        val root = configRoot.toRealPath()
        val namespace = prepareDirectory(root.resolve("samcnpc"))
        val directory = prepareDirectory(namespace.resolve("behaviors"))
        val entries = try {
            Files.list(directory).use { stream -> stream.limit(MAX_DIRECTORY_ENTRIES + 1L).toList() }
        } catch (error: java.io.UncheckedIOException) {
            throw IOException("could not enumerate behavior directory $directory: ${error.message}", error)
        }
        if (entries.size > MAX_DIRECTORY_ENTRIES) throw IOException("behavior directory exceeds $MAX_DIRECTORY_ENTRIES entries")
        val files = entries.filter { it.fileName.toString().endsWith(".json", ignoreCase = true) }.sorted()
        if (files.size > MAX_PACKS) throw IOException("more than $MAX_PACKS JSON files under $directory")
        return files.map { file -> "external:${file.fileName}" to readStable(file, directory) }
    }

    internal fun prepareDirectory(path: Path): Path {
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || path.toRealPath() != path) {
            throw IOException("behavior directory must be a real directory without links: $path")
        }
        return path
    }

    private fun readStable(path: Path, directory: Path): String {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile || path.toRealPath().parent != directory) {
            throw IOException("${path.fileName}: only regular JSON files inside the behavior directory are accepted")
        }
        if (before.size() > BoundedBehaviorJson.MAX_BYTES) throw IOException("${path.fileName}: exceeds ${BoundedBehaviorJson.MAX_BYTES} bytes")
        val body = try {
            Files.newInputStream(path, READ, NOFOLLOW_LINKS).use(BoundedBehaviorJson::read)
        } catch (error: IOException) {
            throw IOException("${path.fileName}: ${error.message ?: "unreadable or invalid UTF-8 file"}", error)
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
            before.fileKey() != after.fileKey() || path.toRealPath().parent != directory) {
            throw IOException("${path.fileName}: file changed during reload; finish saving and reload again")
        }
        return body
    }
}
