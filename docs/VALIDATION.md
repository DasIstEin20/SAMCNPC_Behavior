# Authorized operation inspection — 2026-09-20

Current canonical milestone: clean build, 410 units (46 Core / 345 Behavior / 19 LLM),
209 required native Behavior tests, 12 real client operation scenarios including
9 operator inspection probes, three-mod GUI/client/dedicated HTTP-emulator smoke,
and source/boundary/distribution guards PASS. All 720 frozen source/build hashes
matched the tested campaign. Core is pinned to verified commit 74da575.

Inspection preserves actual definition parameters/versions for all 16 families,
measured progress units and uncertainty. It enforces existing actor/thread/range
checks and returns immutable body/task values. It adds no world scan or executor.
See OPERATION_INSPECTION_API.md and ADR 0090. Standalone clean build and all 345 Behavior unit tests passed.

Earlier campaigns below retain their recorded source scope.

# Operation catalog validation — 2026-09-20

Canonical L0 campaign: clean build, 388 units (45 Core/337 Behavior/6 LLM),
208 required native Behavior tests, 12 actual client cases, both three-mod loading
checks and Java17/source/distribution guards passed. Decoder admission case reached
its navigation target; foreign actor and replay checks passed. 46 order documents
and 276 independent Draft202012 validations cover the catalog and variants.

Standalone checkout: clean build, 337 unit tests and exportOperationCatalog passed
against Core 74d2ba9. This change adds metadata/strict document decoding; existing
executors and persistence format are unchanged. Earlier campaigns below retain
their original source scope and are not claimed as rerun in this milestone.

# Validation scope — 2026-09-13

Recorded campaigns below have distinct source scopes:

- Zoo J/K/L: `a38bd7ec9c69b3400bf5b8f968fe77d320e572ebef6e88c60d5b93fa3add9e75`.
- Bounded input N: `102b5992d9a33f2123255cf2fae671707a0e5211538dc96d2079ce5f9193e3b1`.
  This changes loading/validation, not operation execution or task persistence.

| Check | Observed outcome |
| --- | --- |
| Final canonical campaign | Z8 clean build, source/dependency and distribution guards PASS |
| Units | Core 45, Behavior 328, LLM shell 6; no failures, errors or skipped tests |
| Native mechanics | Behavior 207/207 in Z8; Core 134/134 in Z7 and standalone AA |
| Distinct-JVM restart | 24 checkpoints; fifteen public assignment families, exact inventory/world/task state and rejected assignment replay |
| Lifecycle | 19 scenarios, native stop/disposal and actual author edit/reload |
| Actual client | 12 cases: nine public work assignments/controls/corrections and three public tactics changes with real bow/potion/shield effects |
| Three-mod loading | Actual client title screen and dedicated server; optional provider disabled |
| Standalone Core AA | Clean build, 45 units, 134 native tests; commit `74d2ba9` |
| Standalone Behavior AB | Clean build, 328 units, 207 native tests and 19 lifecycle scenarios against Core `74d2ba9` |
| Distribution | Three Java 17 canonical mod JARs; compiled test drivers excluded; main-source GameTests bundled |
| Earlier terrain J | 21 cases, seed 20260912 |
| Earlier mixed hour K | 3641.1123 active seconds, 72828 ticks, 110 rounds, 660 tasks across eight families |
| Earlier retention/performance K | No retired live runtimes/planning waiters; p95 0.9426 ms, p99 1.3449 ms |
| Earlier navigation L | All 1/8/32/64-NPC idle/active windows and physical arrivals passed |
| Input/schema N/S | Rejected live invalid candidates, Windows lock/junction boundaries, independent checks of 54 raw documents and 16 builtins |

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
Complete operation parameter discovery and final release acceptance remain open.
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

Z/Z4/Z5 exposed an occupied starting-cell regression in shared mining: native node 0
kept aiming into a stationary neighbor even on a requested side route. Z6 reproduced
the fixed collision geometry. The Core correction advanced only a redundant current-cell
node with a clear flat segment; Z7 passed both new Core routes and all nine shared-miner
cases. Z8 then passed the complete grouped suite, and both standalone builds passed.
No test assertions, task limits, collision rules or resource accounting were removed.

Public objective replacement, queued corrections during inventory work, protection/area
reactions and all three client tactics changes retain exact physical outcome checks.
The complete public operation parameter catalog is still pending; these types do not
constitute an LLM provider or an operation JSON parser.

Final canonical source: `60138f836fb336c16b1633db8a288c75ff219464acc51e705c8ebb6a83b56bf1`.
Standalone Core JAR SHA-256: `53b4e18152e7741631d95f600a372d4b3aa0ff7806ac274e66bdc0a08eb001e9`.
Standalone Behavior JAR SHA-256: `20e717e794a456f7d6ee9fe1e890124ea16204b14a9b09bd257d1be5889b1720`.
