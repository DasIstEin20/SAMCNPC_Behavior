# 0117 — Explicit descending tunnel geometry

Status: accepted, 2026-09-23.

The original tunnel keeps a constant floor height. A request to mine underground
and return needs a bounded escape geometry; clearing an excavation box does not
promise to construct or preserve one. LLM must not synthesize a sequence of block
removals, body movements or per-slice commands.

Mining definition v2 adds `tunnel.stepDown`, either zero (default) or one block per
forward slice. Descending tunnels require height 3..4. Origin remains the left
floor-level air cell. Width, length, resource/access filters, travel limits and
ordinary task budgets retain their existing bounds. Core physics and the existing
Behavior mining, pickup, delivery, interruption and return mechanisms execute it.

The work-area box must enclose the exact supplied tunnel, without exclusions. For
a descending tunnel the box also contains unauthorized ceiling and support cells.
Selection, removal receipts and restored pending/cleared/removed evidence therefore
use exact slice membership. Volume counts actual tunnel cells, not the enclosing
box. Dimensions and coordinate limits are validated before index arithmetic.

Definition v1 JSON and persisted data remain horizontal and reject the new field,
including a supplied zero. Current v2 JSON may omit stepDown to obtain zero. V2
persistence writes it explicitly; public inspection preserves the definition's
version and geometry. The task-store envelope does not change. Behavior API 4 and
the corresponding LLM requirement identify the changed public constructor/catalog.
The shorthand mining command remains horizontal; descending work is available
through the public typed operation and strict operation-document path.

This is finite geometry, not an omniscient ore search or arbitrary pit rescue.
External obstructions, fluids and failed routes still stop conservatively. Produced
outputs still require explicit recipients. Multiple outputs share the chosen
counting basis; a mixed quota is not independent minima for cobblestone and coal.
