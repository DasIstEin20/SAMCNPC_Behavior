# External Behavior ZIPs

Put custom archives in `<minecraft-instance>/resources/samcnpc/behaviors/*.zip`.
Loose JSON still belongs in `<minecraft-instance>/config/samcnpc/behaviors/*.json`.
These are SAMCNPC external resources, not vanilla resource packs or datapacks.
Run `/samcnpc behavior reload` as an operator after saving the complete archive.

One archive can contain several documents:

```
my-workers.zip
  behaviors/woodworker.json
  behaviors/helpers/equipment.json
  README.md
```

Rule JSON below the case-sensitive `behaviors/` prefix is compiled. Versioned
mission bundles additionally use `missions/` and `mission-manifest.json`, as
described in [Missions](MISSIONS.md). Other entries,
including unrelated root-level JSON, are ignored as documents but still validated
and counted against every archive limit. Nothing is extracted or executed. ZIP64,
multipart/encrypted ZIPs, self-extracting preambles and special/link entries are
unsupported. Use ordinary stored or deflated ZIP entries with UTF-8 path names.

Built-ins, loose JSON and ZIP documents form one candidate. A read error, malformed
document, unknown component or duplicate pack ID rejects the **entire reload**.
The last accepted registry stays active, including current task execution. External
packs cannot override built-in IDs. Source diagnostics identify the archive member,
for example `external-zip:my-workers.zip!/behaviors/woodworker.json`.

Bounds: 16 ZIP files; 128 entries per ZIP; 4 MiB compressed per ZIP; 128 KiB
decompressed per entry; 2 MiB decompressed per ZIP; 8 MiB decompressed across ZIPs;
compression ratio at most 200:1; 64 external behavior documents total across loose
JSON and ZIPs. Each source directory has at most 1024 entries. Paths have at most
240 characters/eight segments. Absolute paths, backslashes, drive prefixes,
control characters, empty/dot/parent segments, trailing spaces/dots, duplicate
case-insensitive names and symlink/special-file metadata are rejected.

The loader opens regular files without following links, takes a bounded in-memory
snapshot and checks identity/size/modification time again. Concurrent saves fail
with a retry instruction. Save outside the watched directory, then move the
finished archive into place before requesting reload. The existing strict JSON
byte/tree/schema/compiler limits apply unchanged.
