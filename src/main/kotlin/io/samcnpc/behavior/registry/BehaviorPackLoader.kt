package io.samcnpc.behavior.registry

import io.samcnpc.behavior.api.ValidationReport
import io.samcnpc.behavior.model.CompiledPack
import net.minecraftforge.fml.loading.FMLPaths
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class BehaviorRegistrySnapshot internal constructor(
    packs: Map<String, CompiledPack>,
    diagnostics: List<String>,
    internal val fingerprints: Map<String, String> = emptyMap(),
    internal val missions: Map<String, io.samcnpc.behavior.mission.MissionDefinition> = emptyMap(),
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
        var missionDocuments = emptyList<Pair<String, String>>()
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
            val bundle = BehaviorZipFiles.readBundleDocuments(FMLPaths.GAMEDIR.get())
            candidates.addAll(bundle.filter { it.first.substringAfter("!/").startsWith("behaviors/") })
            missionDocuments = bundle.filter { it.first.substringAfter("!/").startsWith("missions/") }
            if (missionDocuments.size > 32) throw java.io.IOException("more than 32 external missions")
            if (candidates.size - BUILTIN_RESOURCES.size > BehaviorPackFiles.MAX_PACKS) {
                throw java.io.IOException("combined loose JSON and ZIP documents exceed ${BehaviorPackFiles.MAX_PACKS} packs")
            }
        } catch (error: java.io.IOException) {
            return failed(messages + "external directories ${externalDirectory()} and ${externalZipDirectory()}: ${error.message}")
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
        val missions = try {
            missionDocuments.map { (source, body) ->
                try { io.samcnpc.behavior.mission.MissionDocuments.read(body) }
                catch (error: IllegalArgumentException) { throw IllegalArgumentException("$source: ${error.message}",error) }
            }
        } catch (error: com.google.gson.JsonParseException) { return failed(messages + "invalid mission JSON: ${error.message}") }
        catch (error: IllegalArgumentException) { return failed(messages + "invalid mission: ${error.message}") }
        if (missions.map { it.id }.distinct().size != missions.size) return failed(messages + "duplicate mission IDs")
        for (mission in missions) if ((mission.stages.map { it.pack } + mission.guards).any { it !in packs })
            return failed(messages + "mission ${mission.id} references a missing pack")
        val fingerprints = compiled.zip(candidates).associate { (pack, source) -> pack.id to io.samcnpc.behavior.mission.MissionDocuments.hash(source.second) }
        val snapshot = BehaviorRegistrySnapshot(packs, messages.toList(), java.util.Map.copyOf(fingerprints), java.util.Map.copyOf(missions.associateBy { it.id }))
        return BehaviorReloadResult(true, ValidationReport(true, messages), snapshot)
    }

    fun externalDirectory(): Path = FMLPaths.CONFIGDIR.get().resolve("samcnpc").resolve("behaviors")
    fun externalZipDirectory(): Path = FMLPaths.GAMEDIR.get().resolve("resources/samcnpc/behaviors")

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
            "data/samcnpc_behavior/behaviors/task_prepare_field.json",
            "data/samcnpc_behavior/behaviors/task_planting.json",
            "data/samcnpc_behavior/behaviors/task_machine.json",
            "data/samcnpc_behavior/behaviors/task_fishing.json",
            "data/samcnpc_behavior/behaviors/task_explorer.json",
            "data/samcnpc_behavior/behaviors/task_combat.json",
            "data/samcnpc_behavior/behaviors/task_inventory.json",
        )
        internal val builtinPackIds: List<String> = java.util.List.copyOf(BUILTIN_RESOURCES.map { "samcnpc:" + it.substringAfterLast('/').removeSuffix(".json") })
    }
}
