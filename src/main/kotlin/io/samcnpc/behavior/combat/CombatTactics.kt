package io.samcnpc.behavior.combat

/** Preference is separate from a hard weapon restriction; neither invents missing equipment. */
internal enum class CombatWeaponPreference { CURRENT, MELEE, RANGED, AUTO }
internal enum class CombatWeaponAllowance { MELEE, RANGED, BOTH }

internal data class CombatTactics(
    val preference: CombatWeaponPreference = CombatWeaponPreference.AUTO,
    val allowed: CombatWeaponAllowance = CombatWeaponAllowance.BOTH,
    val equipArmor: Boolean = true,
    val useShield: Boolean = true,
    val heal: Boolean = true,
    val retreatAt: Double = 0.3,
    val returnAt: Double = 0.65,
    val rangedMinDistance: Double = 4.0,
    val rangedMaxDistance: Double = 14.0,
) {
    fun validationProblem(): String? = when {
        !retreatAt.isFinite() || retreatAt !in 0.0..0.9 -> "retreat health fraction must be in [0, 0.9]"
        !returnAt.isFinite() || returnAt !in 0.1..1.0 || returnAt <= retreatAt -> "return health fraction must exceed retreat threshold"
        !rangedMinDistance.isFinite() || !rangedMaxDistance.isFinite() || rangedMinDistance !in 2.5..12.0 ||
            rangedMaxDistance !in 4.0..24.0 || rangedMaxDistance <= rangedMinDistance -> "ranged distances require 2.5 <= minimum < maximum <= 24"
        preference == CombatWeaponPreference.MELEE && allowed == CombatWeaponAllowance.RANGED -> "melee preference contradicts ranged-only constraint"
        preference == CombatWeaponPreference.RANGED && allowed == CombatWeaponAllowance.MELEE -> "ranged preference contradicts melee-only constraint"
        else -> null
    }

    companion object {
        /** Existing v1 attacks retain their original held-item melee behavior after migration. */
        val LEGACY = CombatTactics(CombatWeaponPreference.CURRENT, CombatWeaponAllowance.MELEE, false, false, false, 0.0)
    }
}