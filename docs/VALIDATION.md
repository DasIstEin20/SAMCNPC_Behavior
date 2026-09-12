# Validation scope — 2026-09-13

Two canonical source snapshots have recorded evidence:

- Zoo J/K/L: `a38bd7ec9c69b3400bf5b8f968fe77d320e572ebef6e88c60d5b93fa3add9e75`.
- Bounded input N: `102b5992d9a33f2123255cf2fae671707a0e5211538dc96d2079ce5f9193e3b1`.
  This changes loading/validation, not operation execution or task persistence.

| Check | Observed outcome |
| --- | --- |
| Clean build and source/dependency checks | J and N PASS |
| JUnit | N: Core 45, Behavior 290, LLM shell 1; no failed/skipped tests |
| Forge GameTests | J: Core 132/132; J and N: Behavior 195/195 |
| Distinct-JVM restart | J: 24 live checkpoints plus the dead courier case; original budgets and exact resources |
| Lifecycle | J and N: 18 scenarios, native stop/disposal, old action handles rejected |
| Terrain Zoo | J: 21 cases, seed 20260912 |
| Real client | J and N: 12 physical operation cases; Core numeric config save/lock/two-world/reload separately exercised |
| Three mod loading | J and N: client title screen and dedicated server PASS; provider disabled |
| Mixed full hour | K: 3641.1123 active seconds, 72828 active ticks, 110 rounds, 660 exact tasks, eight families, native stop |
| Hour retention and performance | Zero retired live runtimes/planning waiters; p95 0.9426 ms, p99 1.3449 ms; all predefined limits passed |
| Navigation performance | L: eight original 1/8/32/64-NPC idle/active windows passed all predefined limits and physical arrivals |
| Bounded input/reload | N: nine new unit groups and ten rejected disk candidates; active task/action unchanged on rejection |
| Windows file boundaries | N: ordinary read, exclusive-lock rejection/recovery, two real directory junction rejections; original file unchanged |
| Distribution | Three named Java 17 canonical JARs; `src/test` drivers excluded. Main-source GameTests remain bundled. |
| Standalone Core | Clean build, 45 units, 132 native cases passed; local source commit `7e2bcb9` |
| Standalone Behavior | Clean build, 290 units, 195 native cases and 18 lifecycle scenarios passed against Core `7e2bcb9` |

The full hour uses six concurrent roles rotating across eight finite families. Setup,
cleanup, pauses, idle gaps and long clock gaps do not count toward its active duration.
Serialized terminal reports are bounded history, not retained live entity/runtime objects.
The 64-NPC navigation window measured p95 1.8475 ms and p99 2.3904 ms; it does not represent
64 NPCs mining concurrently or arbitrary modpack load.

Actual external machine proof covers Iron Furnaces 4.1.8, Modrinth version KHAcRQwi,
with its automation faces configured, in its separately recorded Z2 campaign. J/K/L/N
did not re-run that external mod. The optional dev-only JAR is not redistributed.
No claim extends to untested machines or player-only mod APIs.

Retained regressions include shared-miner drop delivery, slow physical pickup
(217 collection ticks with the original budget), passive pickup counted once, and intentional
navigation cancellation detached before the next route. The cancellation reproducer failed
before repair and passed four fresh eight-case runs afterward, followed by the full suite.
The old input gateway reproduced duplicate/lenient JSON acceptance, fractional-integer
rounding and incorrect supplementary-Unicode length handling before the N repair.

Skin verification with two authenticated accounts remains a separate presentation gate.
Full operation API/catalog/schema finalization and release acceptance remain open.
This is a development snapshot with the recorded scope above.
