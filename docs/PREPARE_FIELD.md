# Preparing a field

`samcnpc:prepare_field` uses a hoe already carried by the NPC to turn a supplied soil
plane into farmland. It does not plant seeds, harvest crops, place water, remove
obstructions or acquire tools. Use inventory work to obtain a hoe beforehand.

The area contains **soil block coordinates**, one block below the feet of an NPC
standing on full dirt. Its inclusive bounding rectangle is limited to 64 cells at one
height; explicit exclusions are respected. The anchor and optional return point use
feet coordinates. The whole operation stays inside its fixed travel radius.

Example operation document:

```json
{
  "documentVersion": 1,
  "type": "samcnpc:prepare_field",
  "definitionVersion": 1,
  "parameters": {
    "dimensionId": "minecraft:overworld",
    "area": {
      "bounds": {
        "min": {"x": -8, "y": 62, "z": -4},
        "max": {"x": -6, "y": 62, "z": -2}
      }
    },
    "anchor": {"x": -10.5, "y": 63, "z": -3.5},
    "returnTo": {"x": -10.5, "y": 63, "z": -3.5}
  }
}
```

Behavior selects a carried hoe, approaches each cell and supplies the actual observed
surface hit to Core. Existing farmland needs no tool use. A blocked cell, unavailable
observation, unsuitable soil or missing hoe produces an explicit finite failure. The
task may return with partial results; a summary is not a completion receipt.

After pause or load, saved farmland is rechecked. A cell has at most two native-use
attempts across the whole task, including restart and changes to previously prepared
soil. Unknown callback effects terminate without automatic replay. A successful
`FIELD_PREPARED` result requires the actual authorized farmland and clear air above,
verified after the requested physical return. Later drying or trampling is outside
this finite operation's guarantee. There is no crop output or delivery counter.

The LLM may request this operation through the public Behavior order only. The
experimental SAM expression uses the same catalog and decoder; its text is never
Python execution. Structured goal constraints must explicitly permit the area,
soil change and return, with no item quantity semantics.
