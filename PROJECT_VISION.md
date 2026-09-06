# Project vision

SAMCNPC should feel less like “a mob with a player skin” and more like **a player-shaped actor controlled by explicit behavior data**.

The core idea is separation:

- **Core** knows what an NPC *can do* and how to represent/synchronize it safely.
- **Behavior** knows what an NPC *chooses to do* using reloadable, validated data.
- **LLM** may later translate natural-language intent into behavior selections/parameters, but it never becomes a privileged god-mode controller.

## Non-goals for the reboot foundation

- pretending the NPC is a real authenticated player/server connection;
- a general scripting language;
- arbitrary Java/Kotlin class loading from JSON;
- a huge GUI before mechanics work;
- cloud dependency in Core/Behavior;
- dozens of behavior concepts before follow/idle/combat are reliable;
- perfect emulation of every edge case of `Player` in the first milestone.

## Design test

For every new feature ask:

1. Is this a **mechanism**? Put the smallest safe primitive in Core.
2. Is this a **decision/policy**? Put it in Behavior, ideally as pack data + registered primitive.
3. Is this **natural-language/model integration**? It belongs in LLM and must go through Behavior validation.

If the answer is unclear, do not add the feature until its ownership is clear.
