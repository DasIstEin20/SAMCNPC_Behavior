package io.samcnpc.behavior.registry

import io.samcnpc.behavior.api.ValidationReport
import io.samcnpc.behavior.model.CompiledPack
import net.minecraftforge.fml.loading.FMLPaths
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class BehaviorRegistrySnapshot(
    packs: Map<String, CompiledPack>,
    diagnostics: List<String>,
) {
    val packs: Map<String, CompiledPack> = java.util.Map.copyOf(packs)
    val diagnostics: List<String> = java.util.List.copyOf(diagnostics)
    companion object {
        val EMPTY = BehaviorRegistrySnapshot(emptyMap(), listOf("No behavior packs have been activated; NPCs are safely idle."))
    }
}

data class BehaviorReloadResult(
    val activated: Boolean,
    val report: ValidationReport,
    val snapshot: BehaviorRegistrySnapshot,
)

/** Filesystem work is performed only at startup/reload, never from the NPC tick path. */
class BehaviorPackLoader(private val compiler: BehaviorPackCompiler) {
    fun loadCandidate(): BehaviorReloadResult {
        val messages = mutableListOf<String>()
        val candidates = mutableListOf<Pair<String, String>>()
        for (resource in BUILTIN_RESOURCES) {
            val source = "builtin:$resource"
            val body = try {
                resourceText(resource)
            } catch (error: java.io.IOException) {
                return failed(messages + "$source: could not read required built-in behavior resource: ${error.message}")
            }
            if (body == null) {
                return failed(messages + "$source: missing required built-in behavior resource")
            }
            candidates.add(source to body)
        }
        try {
            candidates.addAll(BehaviorPackFiles.readExternal(FMLPaths.CONFIGDIR.get()))
        } catch (error: java.io.IOException) {
            return failed(messages + "external directory ${externalDirectory()}: ${error.message}")
        }


        val compiled = mutableListOf<CompiledPack>()
        for ((source, body) in candidates) {
            val result = compiler.compile(source, body)
            messages.addAll(result.report.messages)
            val pack = result.pack ?: return failed(messages)
            compiled.add(pack)
        }
        val duplicate = compiled.groupBy { it.id }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicate.isNotEmpty()) {
            return failed(messages + "duplicate pack IDs are rejected transactionally: ${duplicate.joinToString(", ")}")
        }
        val packs = compiled.sortedBy { it.id }.associateBy { it.id }
        val snapshot = BehaviorRegistrySnapshot(packs, messages.toList())
        return BehaviorReloadResult(true, ValidationReport(true, messages), snapshot)
    }

    fun externalDirectory(): Path = FMLPaths.CONFIGDIR.get().resolve("samcnpc").resolve("behaviors")

    private fun resourceText(path: String): String? {
        val classpathStream: InputStream? = javaClass.classLoader.getResourceAsStream(path)
        if (classpathStream != null) {
            return classpathStream.use(BoundedBehaviorJson::read)
        }
        // ForgeGradle's command-line userdev runs expose compiled classes but, without an IDE
        // plugin, do not put source resources on that class loader. The exact bundled resource is
        // staged under the game directory by the Behavior run task; production JARs use above.
        val staged = FMLPaths.GAMEDIR.get().resolve(path)
        if (!Files.isRegularFile(staged)) {
            return null
        }
        return Files.newInputStream(staged, java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            .use(BoundedBehaviorJson::read)
    }

    private fun failed(messages: List<String>): BehaviorReloadResult =
        BehaviorReloadResult(false, ValidationReport(false, messages), BehaviorRegistrySnapshot.EMPTY)

    companion object {
        private val BUILTIN_RESOURCES = listOf(
            "data/samcnpc_behavior/behaviors/idle_look.json",
            "data/samcnpc_behavior/behaviors/follow_summoner.json",
            "data/samcnpc_behavior/behaviors/retaliate.json",
            "data/samcnpc_behavior/behaviors/demo_lumberjack.json",
            "data/samcnpc_behavior/behaviors/task_navigation.json",
            "data/samcnpc_behavior/behaviors/task_delivery.json",
            "data/samcnpc_behavior/behaviors/task_lumberjack.json",
            "data/samcnpc_behavior/behaviors/task_mining.json",
            "data/samcnpc_behavior/behaviors/task_food.json",
            "data/samcnpc_behavior/behaviors/task_farming.json",
            "data/samcnpc_behavior/behaviors/task_planting.json",
            "data/samcnpc_behavior/behaviors/task_machine.json",
            "data/samcnpc_behavior/behaviors/task_fishing.json",
            "data/samcnpc_behavior/behaviors/task_explorer.json",
            "data/samcnpc_behavior/behaviors/task_combat.json",
            "data/samcnpc_behavior/behaviors/task_inventory.json",
        )
    }
}
