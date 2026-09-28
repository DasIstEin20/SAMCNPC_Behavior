package io.samcnpc.behavior.api

import java.nio.file.Files
import java.nio.file.Path

object BehaviorCatalogExport {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val target = Path.of(args[0])
        Files.createDirectories(target.toAbsolutePath().parent)
        Files.writeString(target, BehaviorSchemaApi.registeredSchema() + "\n")
        println("Exported registered Behavior catalog ${BehaviorCatalogApi.snapshot().catalogVersion} to $target")
    }
}
