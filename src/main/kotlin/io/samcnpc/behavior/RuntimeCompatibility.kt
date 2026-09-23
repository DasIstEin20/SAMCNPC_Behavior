package io.samcnpc.behavior

import net.minecraftforge.fml.ModList

/** Forge metadata is available before linking any dependent public API class. */
internal object RuntimeCompatibility {
    fun verify() {
        val own = ModList.get().mods.first { it.modId == "samcnpc_behavior" }.modProperties
        val required = own["requiresCoreApi"] as? String
            ?: error("Behavior build lacks required Core API metadata")
        val installed = ModList.get().mods.firstOrNull { it.modId == "samcnpc_core" }?.modProperties.orEmpty()
        check(installed["apiVersion"] == required) {
            "Behavior requires Core API $required, installed ${label(installed["apiVersion"])} " +
                "(build ${label(installed["buildId"])}). Install matching SAMCNPC JARs."
        }
    }

    private fun label(value: Any?): String = (value as? String)?.takeIf { text ->
        text.length in 1..64 && text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
    } ?: "missing/invalid"
}
