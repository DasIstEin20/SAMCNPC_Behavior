# Validation scope — 2026-09-13

Recorded campaigns below have distinct source scopes:

- Zoo J/K/L: `a38bd7ec9c69b3400bf5b8f968fe77d320e572ebef6e88c60d5b93fa3add9e75`.
- Bounded input N: `102b5992d9a33f2123255cf2fae671707a0e5211538dc96d2079ce5f9193e3b1`.
  This changes loading/validation, not operation execution or task persistence.

| Check | Observed outcome |
| --- | --- |
| Clean build and source/dependency checks | J/N/Q/R3/S PASS |
| JUnit | S: Core45, Behavior307, LLM shell3; no failed/skipped tests |
| Forge GameTests | Core132/132 (J/O), Behavior197/197 (S) |
| Distinct-JVM restart | R3:24 live checkpoints plus dead courier; exact resources/budgets and v9 control revisions |
| Lifecycle | T:19 scenarios including actual author edit/reload; native stop/disposal, old action handles rejected |
| Terrain Zoo | J: 21 cases, seed 20260912 |
| Real client | S:12 physical cases, nine public control/amendment probes; Core config save/lock/two-world/reload separately exercised |
| Three mod loading | S:client title screen and dedicated server; public catalog/schema available, provider disabled |
| Mixed full hour | K: 3641.1123 active seconds, 72828 active ticks, 110 rounds, 660 exact tasks, eight families, native stop |
| Hour retention and performance | Zero retired live runtimes/planning waiters; p95 0.9426 ms, p99 1.3449 ms; all predefined limits passed |
| Navigation performance | L: eight original 1/8/32/64-NPC idle/active windows passed all predefined limits and physical arrivals |
| Bounded input/reload | N: nine new unit groups and ten rejected disk candidates; active task/action unchanged on rejection |
| Windows file boundaries | N: ordinary read, exclusive-lock rejection/recovery, two real directory junction rejections; original file unchanged |
| Distribution | Three named Java 17 canonical JARs; `src/test` drivers excluded. Main-source GameTests remain bundled. |
| Standalone Core | Clean build, 45 units, 132 native cases passed; local source commit `7e2bcb9` |
| Standalone Behavior | U2:clean build,307units,197native and19lifecycle passed against Core7e2bcb9;201 compiled test classes excluded |

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
Typed assignment/full operation parameter catalog and final release acceptance remain open.
This is a development snapshot with the recorded scope above.

Q/R3/S add public metadata and supervision after the original hour:
Q catalog source8d0803416673a5ec546e34e883b23895ba5f083d12e70e288ec886fbab702de8;
R3 control/persistence source8f68c66a7e55a9292499d2b6565da275bdea6aefe147040753c5ee72502e731c;
S amendments/schema source38f6bc091a0faf46c2a0102acbce5dd7132b2fa3d05050e4a9019a6dcf5bad23.
S verifies queued receipt expiry, actual source/cargo/time corrections, exact replay and
conflicting reuse, without changing v9 encoding. Independent Draft202012 checks compare
54 identical raw documents and16 builtins with the runtime gateway; explicit runtime
semantic checks go beyond structural/schema acceptance. The full hour and navigation
performance remain J/K/L proofs and were not rerun for the later metadata/API changes.

T changes only test/author checks, with unchanged S production. Its clean build355units
and19 lifecycle cases passed; the documented follow example was edited8->2 and the
same NPC physically approached from7.9728 to1.9291blocks after reload, holding20ticks
before the edit. The source snapshot is544bc3f750faadb468f0192ea338e049b73da28b91cec82bef9d68d41b8db9b7.

The initial standalone U run exposed a test-only relative path assumption in the schema/
author check. Resolving its path absolutely fixed it without removing assertions. The
canonical three schema tests and full standalone U2 then passed. The packaged Behavior
JAR SHA-256 is8bc2058d68fafae6d2c081e2caa6af0d3439d1999930adfa422aba01c6acf5f5.
