# ADR 0090: Authorized operation inspection

Date: 2026-09-20. Status: accepted and verified.

Behavior will expose a read-only, on-demand inspection gateway using the existing
TaskSupervision.withNpc authorization. Capture happens on the server thread.
Workers receive copied physical state, own-body details and bounded task values;
never a facade, entity, NBT, task runtime or action dispatcher.

Actual task parameters use the catalog field names and explicit values, including
defaults already resolved when the task was accepted. Preserve the original
definitionVersion. Legacy delivery v1 has no anchor; inventing an anchor and
presenting a v2 order would silently change the stored intent. Legacy attacks
retain their original tactical settings and v1 lumberjack semantics remain v1.
The inspection is descriptive, not a new assignment document or migration.

A small closed OperationValue tree carries immutable scalars/records/sequences.
No reflection, Gson/NBT objects, generic runtime-class serialization, executable
expressions or parsing occurs in capture. Family adapters explicitly enumerate
supported definitions. JSON serialization belongs to the consumer after capture.
Current-version projections must decode through the existing operation parser
with unchanged effective parameters; legacy projections remain explicitly legacy.

Progress is copied from measured family state. Distinguish delivered items,
removed resource blocks, completed planting layouts, actual placed saplings and
confirmed defeats. An uninitialized progress state is unavailable, not zero.
Checkpoints and intent coordinates are not fresh facts about the world.
No world scan or container-content observation is added by this gateway.

Verified 2026-09-20: clean build, 410 units (46/345/19), 209 required Behavior
GameTests, 12 actual client operation scenarios including 9 operator inspection
probes, and three-mod GUI/client/dedicated HTTP-emulator smoke. Source/boundary/
distribution checks passed. All 720 frozen source/build files matched.
Evidence: autonomy/run-20260920-llm/inspection-evidence.json.
