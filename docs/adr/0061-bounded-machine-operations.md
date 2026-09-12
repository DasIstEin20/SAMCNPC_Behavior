# ADR 0061: Bounded machine tasks over actual Core transfers

Status: accepted; bounded native/external processing and restart verified. 2026-09-12.

Machine work is a typed Behavior task and an ordinary registered pack action. Core
resolves the supplied block/automation face afresh and reports actual moved counts.
Behavior never receives a BlockEntity, capability or recipe implementation. The first
slice accepts up to four fixed input ports and one output port on one machine, carried
feed quantities, expected output quantity, travel bounds, polling/no-progress limits,
and optional physical return. It does not synthesize processing or item conversion.

Inputs and output use different item identities; the operation must not satisfy itself
by extracting its own just-deposited input. Confirmed feed counters never reset after
pause, combat, save/load or capability re-acquisition. Output counters mean actual
items extracted from the specified output; they do not claim exclusive recipe ownership.
Initial/foreign machine output is observable and can be collected only up to the requested
quota. Native acceptance starts with empty output when proving real processing.

Every decision makes at most one real transfer. Failed input ports rotate so a blocked
input cannot starve fuel or output collection. Polling remains bounded, and a processing
wait cannot reset the task deadline. A no-progress report describes observations; it
must not invent a missing-energy or recipe diagnosis without a supplied capability.
Changed type/slot shape, missing endpoint or Core uncertainty stops explicitly, keeping
confirmed partial counts. Same-type replacement is freshly resolved; no old capability
is retained or used and already confirmed feeds are never replayed.

A shared resource receipt operation attributes an actual insert/extract to delivered/
supplied gross counters after checking the NPC delta. It does not infer transfer amounts
from a machine's net slot delta while the machine can process items in callbacks.
The finite state stores counters, observations and relative remaining clocks, never
live handles. Task-store version 6 adds this state; versions 1â€“5 continue to load and
retain their existing migrations. Unknown future formats remain preserved and idle.

Limits: no automatic recipe discovery, crafting, fuel guessing, energy automation,
input resupply or compensation across independently saved foreign chunks. The caller
supplies known bounded ports; later public API/JSON uses the same validation. Standard
pause/cancel/combat interruptions apply. Definition amendments are rejected while a
machine operation holds its fixed feed contract; callers can cancel and issue a new
contract using the actual remaining inventories.

Required evidence: validation/codec/resource mismatch tests, real vanilla processing,
sided provider partial operations, output pressure/no progress/removal, real JVM restart,
and one pinned external machine through the same code. Full grouped clean/client/native
and long-run gates remain in the Acceptance Zoo plan.

Initial native run c: all six furnace tasks lost container observation at the measured
world adapter, which inherited the new optional API default. MeasuredWorldView now
transparently delegates the API and counts endpoint reads; a live-change regression
checks forwarding and absence of caching. Run c and its world are preserved.

Native run d passed seven required cases and 254 Behavior unit tests. Run e passed
all 174 Behavior GameTests and 20 live task checkpoints plus one dead courier across
separate JVMs, including partially fed vanilla processing without replay. Evidence is
`autonomy/run-20260912-zoo/z2-machine-restart-evidence.json`. External proof is pending.

Run i passed configured Iron Furnaces 4.1.8 processing and actual restart in the same
20-live/one-dead campaign. The exact official artifact SHA-256 is in compatibility
evidence. Test-only port/NBT setup represents player automation settings; production
contains no Iron Furnaces branch. A non-consumable dev runtime configuration avoids
publishing the mapped fixture transitively. First-party project dependencies use normal
Gradle project references: fg.deobf(project) also registered those already-mapped projects
as obfuscated origins and broke external remapping. fg.deobf remains on the external JAR.
