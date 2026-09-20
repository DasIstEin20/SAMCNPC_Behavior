package io.samcnpc.behavior.api

import java.nio.file.Files
import java.nio.file.Path

/** Build-time author artifacts; never bundled with production code. */
object OperationCatalogExport {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Expected one output directory" }
        val output = Path.of(args.single())
        Files.createDirectories(output)
        Files.writeString(output.resolve("operation.schema.json"), OperationDocumentApi.orderSchema() + "\n")
        Files.writeString(output.resolve("operation-change.schema.json"), OperationDocumentApi.changeSchema() + "\n")
        Files.writeString(output.resolve("operation-catalog.json"), OperationDocumentApi.catalogJson() + "\n")
        println("Exported 16 operation descriptors, 8 change descriptors and both schemas to $output")
    }
}
