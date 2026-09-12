# ADR 0075: Bounded and unambiguous Behavior input

Status: accepted and verified for this input slice. 2026-09-13.

The current compiler creates a Gson tree before input/depth checks. Duplicate object
members disappear during parsing, and integer validators can round fractional input
through Double. External discovery checks size before an unbounded read. P11 needs one
bounded path for built-ins, external files and the public candidate validator.

Use the existing pinned Gson 2.10 streaming reader to construct a bounded tree. A small
lexical pass rejects raw controls, invalid escapes and uppercase literals that this reader
accepts even without leniency; Gson retains responsibility for grammar and decoding.
Source inspected: [Gson 2.10 JsonReader](https://github.com/google/gson/blob/gson-parent-2.10/gson/src/main/java/com/google/gson/stream/JsonReader.java).
No parser/runtime dependency changes and no JSON work in the NPC tick path.

Limits: 128 KiB actual UTF-8 bytes, nesting depth 32 below the root, 16384 JSON values,
512 Unicode code points per string and 64 characters per numeric token. Duplicate decoded
member names are rejected before insertion. Strings must contain valid Unicode; file
UTF-8 decoding reports malformed data. Existing condition depth 8, 256 rules and 16 actions
remain semantic limits. Exact decimal-to-long conversion validates integer fields before
range checks; numerical 1.0 and 1e0 remain integers. String lengths follow JSON Schema code
points, including supplementary characters.

Read at most limit+1 bytes from the opened stream. External discovery examines at most
1024 immediate entries and accepts at most 64 JSON files in stable order. The config root
is server supplied. The samcnpc/behaviors directories must be real directories without
symbolic-link/junction redirection; JSON entries must be regular files in that directory.
Open files without following links and compare identity/size/modification observations
around the read. A changed/unavailable file or any invalid candidate rejects the whole
reload; the active registry and ongoing NPC tasks remain untouched. Operators should
save a complete temporary file and atomically replace the final JSON before reloading.

This does not create a transaction against a hostile operating-system process or change
arbitrary world-file persistence guarantees. No task-state format or accepted operation
meaning changes in this slice. Behavior document schema remains version 1; previously
ambiguous/malformed data is rejected with source/context. Full schema/operation catalog
and authorized control APIs are subsequent P11 slices.

Verification required: old-parser failures for duplicate/fraction/lenient cases; input and
filesystem regressions; built-in and schema-conformance positives; live malformed/duplicate/
unknown/schema/channel/encoding/size reloads that preserve exact task NBT and action handle;
Windows link/locked-file checks; clean build and relevant native/lifecycle gates.

The original compiler reproduced three assertion failures: accepted duplicate/lenient JSON,
rounded fractional priority, and rejected a valid 512-code-point description. Original log
and XML are retained with the development validation records.

Grouped run p11inputn passed clean build/source/distribution checks, 336 units (45 Core,
290 Behavior, 1 LLM), 195 Behavior native cases, 18 lifecycle scenarios including all ten
rejected disk candidates, 12 physical client cases and three-mod client/server loading.
Isolated Windows probes passed ordinary read, exclusive lock rejection, recovery after
unlocking and both namespace/directory junction rejection without changing the file.
Evidence summary: [validation scope](../VALIDATION.md).
Source hash 102b5992d9a33f2123255cf2fae671707a0e5211538dc96d2079ce5f9193e3b1 (578 files).
This verifies the input repair, not the complete P11 schema/catalog/control API.
