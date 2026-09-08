<p align="center">
  <img src="assets/samcnpc-behavior-logo.png" width="720" alt="SAMCNPC Behavior" />
</p>

# SAMCNPC Behavior

English | [Polski](README.pl.md)

**Deterministic decisions and reloadable behavior packs for player-like NPCs — Minecraft Forge 1.20.1.**

[SAMCNPC Core](https://github.com/DasIstEin20/SAMCNPC_Core) provides the NPC's body and mechanics.
Behavior decides when and why to use them: following the summoner, looking at a target, responding
to an attacker, or running a bounded lumberjack job. It controls NPCs through Core's public API.

Behavior requires Core. It does not require an LLM, API keys, or a cloud service.
Installing it does not automatically assign behavior packs to newly summoned NPCs.

## Features

- **Declarative JSON packs:** versioned conditions and actions, rule priorities, cooldowns,
  and `all` / `any` / `not` condition expressions. JSON is data, never executable code.
- **Deterministic arbitration:** actions compete for explicit channels. Rule priority, pack
  priority, pack ID, rule ID, and action index provide stable tie-breaks. A multi-channel
  action runs only if all its channels are available.
- **Transactional reload:** built-in resources and custom server files are validated before
  activation. A rejected reload keeps the last known-good registry; duplicate IDs do not override it.
- **Persistent assignments:** each NPC can have up to eight explicitly assigned packs.
  Behavior stores its own assignments and durable lumberjack job state separately from Core.
- **Diagnostics and recovery:** commands expose active packs, selected actions, and job progress;
  bounded movement, pickup, elevation, and work-coordination helpers support the lumberjack demo.
- **Strict dependency boundary:** world actions go through Core on the server thread.
  Behavior does not import Core entity/rendering internals or depend on the optional LLM module.

The runtime follows `snapshot -> evaluate -> arbitrate -> execute -> record`.
Channels are `movement`, `look`, `main_hand`, `off_hand`, `combat`, `interaction`, `block_action`,
and `inventory`. See the [project vision](PROJECT_VISION.md) for the wider module split.

## Requirements

| Component | Version used for builds and tests |
| --- | --- |
| Minecraft Java Edition | 1.20.1 |
| Minecraft Forge | 47.4.21 |
| Kotlin for Forge | 4.12.0 |
| SAMCNPC Core | 0.1.0 |
| Java | 17 |

Install Behavior, Core, and Kotlin for Forge on both the client and server.
For single-player, install them in your Forge client profile.

## Building and installation

Use JDK 17 and the included Gradle wrapper. Core is a Git submodule at `core/`, pinned to a
specific published commit rather than a floating branch. Cloning requires access to both repositories.
The current pin includes Core's Forge configuration, tool modes, effects and double-chest fixes. Use the matching
Core JAR from this build; older development builds may also carry the `0.1.0` version number.

```powershell
git clone --recurse-submodules https://github.com/DasIstEin20/SAMCNPC_Behavior.git
cd SAMCNPC_Behavior
.\gradlew.bat clean build
```

On Linux/macOS, use `./gradlew clean build`. If the repository was cloned without submodules,
run `git submodule update --init --recursive` first. Run it again after pulling a change to the Core pin.
The first build downloads the pinned dependencies.

The build produces two separate mod JARs:

- `build/libs/samcnpc-behavior-0.1.0.jar`
- `core/build/libs/samcnpc-core-0.1.0.jar`

Behavior does not bundle Core into its JAR. Close the game/server before copying these JARs into
`mods`, replacing their previous versions. Keep Kotlin for Forge installed. LLM sources are not needed.

## Getting started

Summon an NPC with Core, then assign the desired Behavior packs:

```text
/samcnpc summon Sam
/samcnpc behavior packs
/samcnpc behavior assign Sam samcnpc:follow_summoner,samcnpc:retaliate
/samcnpc behavior diagnostics Sam
```

Assignment replaces the NPC's current pack list; separate multiple IDs with commas.
NPC-specific commands accept a name, UUID, or unambiguous prefix within the current dimension's
256-block lookup radius. Only the summoner or an operator may control or inspect that NPC.

| Command | Action |
| --- | --- |
| `/samcnpc behavior packs` | List active pack IDs. |
| `/samcnpc behavior assign <npc> <pack_ids>` | Replace the assigned pack list. |
| `/samcnpc behavior diagnostics <npc>` | Show assigned packs, selected intents, and the latest problem. |
| `/samcnpc behavior reload` | Validate and reload packs; requires operator permission level 2. |
| `/samcnpc behavior lumberjack <npc>` | Start the lumberjack demo. |
| `/samcnpc behavior lumberjack status <npc>` | Show job phase, target, scan progress, and recovery state. |
| `/samcnpc behavior lumberjack cancel <npc>` | Cancel the demo and restore its previous pack assignments. |

## Built-in packs

| Pack ID | Purpose |
| --- | --- |
| `samcnpc:idle_look` | Low-priority looking at the summoner. |
| `samcnpc:follow_summoner` | Move toward the summoner when far away and look at them nearby. |
| `samcnpc:retaliate` | Acquire the NPC's recent attacker, approach it, and request melee attacks. |
| `samcnpc:demo_lumberjack` | Drive the experimental lumberjack job; start it through the dedicated command. |

The [built-in JSON files](src/main/resources/data/samcnpc_behavior/behaviors) are editable examples
of the same pack format used for custom behaviors. The follow/combat actions currently use direct
steering; they are not full obstacle-solving navigation policies.

## Custom behavior packs

Place UTF-8 `.json` files directly in the server's `config/samcnpc/behaviors/` directory
(`run/config/samcnpc/behaviors/` in a dev run), then use `/samcnpc behavior reload`.
Use a new namespaced ID: custom files cannot override built-ins or each other by reusing an ID.

To make a first custom pack, copy [idle_look.json](src/main/resources/data/samcnpc_behavior/behaviors/idle_look.json),
change its `id` to `myserver:idle_look`, and save it as `my_idle_look.json` in that directory.
After a successful reload, assign it with:

```text
/samcnpc behavior assign Sam myserver:idle_look
```

The [v1 schema](src/main/resources/samcnpc/behavior-pack.schema.json) describes the document shape.
The Kotlin compiler also validates registered action/condition IDs and their arguments.
See [BehaviorDefinitions](src/main/kotlin/io/samcnpc/behavior/registry/BehaviorDefinitions.kt)
for the actual allow-list. Unknown fields, unsupported IDs, invalid arguments, and channel violations
are rejected; packs cannot load classes, scripts, commands, or HTTP integrations.

Current limits include 64 external files, 128 KiB per external file, 256 rules per pack,
16 actions per rule, and eight levels of condition expressions. Files are loaded at startup/reload,
not scanned or parsed on every NPC tick. Missing assigned packs suspend that NPC's rule execution
with a safe-idle diagnostic.

## Lumberjack demo

This is an **experimental integration job that changes real blocks**. Try it in a backed-up test world.

Place an accessible chest near the NPC with an axe and, optionally, armor and building blocks.
Supply a shovel for dirt supports or a pickaxe for cobblestone/netherrack supports; the job takes
these tools from the chest. Without building blocks it can recover and reuse real felled wood.
Keep reachable single-column trees within the bounded 50×50 work area around the NPC's
starting position, then run:

```text
/samcnpc behavior lumberjack Sam
/samcnpc behavior lumberjack status Sam
```

Core's **Mods → SAMCNPC Core → Config** screen controls physical NPC settings. Global
Yes/No forces every save; Global Default delegates to In world settings. With **Ignore
missing tool = Yes**, an axe-less chest is allowed and the NPC works by hand, still taking
an available axe. **Bare hands only = Yes** skips collecting tools and always works with
an empty hand. Both default to No; Bare hands only takes precedence. Vanilla harvest rules
still apply. Behavior's logo is also visible in the Forge Mods list.

The job chooses a nearby chest, collects available equipment, searches for wood, moves to a working
stance, breaks selected logs, picks up their drops, and returns gathered wood to the chest.
Its helpers support limited foliage clearing, trunk stepping, inventory-backed temporary pillars,
capacity-triggered deposits, stuck detection, and coordination between workers.
Foliage clearing preserves the suspended work and any existing scaffold stance. Temporary wooden
supports are not mistaken for remaining trunk logs. Placement is checked synchronously before a
later pickup can change the held stack, and cleanup/settled-drop collection precede final deposit.
Stump climbing keeps its jump destination until an actual supported landing; reaching stump
height while airborne is not enough. After the upper trunk is gone, the NPC dismantles its
scaffold before moving away to cut the retained stump underneath it.

Both halves of a double chest now address its combined inventory through Core. On the way
to a chest, the job may clear a visible blocking leaf or log, collect cut wood and resume the
suspended task. It does not repeatedly target a hidden leaf through a fern/trunk. Elevated
foliage clearing retains the existing scaffold stance, and full pillar retries consume the
bounded recovery budget. Saved job format 18 preserves these continuations when loading
older jobs. Previously cancelled jobs still require a new start command.

Scaffolds are limited to eight levels. A collection attempt has a 240-tick total budget and requires
20 grounded quiet ticks to finish normally; successful pickups do not reset the deadline. Material
recovery is limited to three attempts per tree. Unreachable work/drops produce bounded recovery
or diagnostics rather than an endless loop. Job state and continuations survive save/load, with
migration of older in-flight placement state.

Core still performs every physical action; the lumberjack's targets and work plan belong to Behavior.
The demo temporarily replaces the NPC's packs and restores them on completion or cancellation.
Cancellation or unsafe/interrupted cleanup may leave already placed supports behind, with
remaining positions logged. Cleanup has a 600-tick deadline and refuses fluids, climbing or riding.
Vanilla oak/birch crowns and nine-block
vertical trunks are tested. Large branching/2×2 trees, arbitrary terrain/modpacks and multi-NPC
recovery are not a verified capability; this remains a bounded demo, not a general forestry AI.

## Development and tests

```powershell
.\gradlew.bat clean build
.\gradlew.bat :runGameTestServer
.\gradlew.bat :runClientLumberjackSmoke
.\gradlew.bat :runClientLumberjackSmoke -PlumberjackGuiProbe=dirt
.\gradlew.bat :runClientLumberjackSmoke -PlumberjackGuiProbe=wood
.\gradlew.bat :runClientConfigSmoke
.\gradlew.bat :runClient
.\gradlew.bat :runServer
python tools/check_behavior_boundary.py
python core/tools/check_core_boundary.py
```

The leading `:` selects Behavior's run task; Core also exposes its own dev runs.
Behavior's client/server runs load both mods. A normal `:runServer` launch requires the user to
accept Minecraft's EULA.
GameTests use their own flat world in `run-gametest/`; ordinary dev worlds in `run/` are not reused.

Unit tests cover pack validation, arbitration, navigation/elevation/work helpers, collection budgets,
and saved-state migration. Eighteen dedicated GameTests exercise chest equipment, foliage clearance,
elevated work, wood/dirt/cobblestone scaffolds, pickup during placement, edge footing, nested recovery,
complete cleanup, unsafe/deadline recovery and exact wood conservation. Flat-ground regressions
also require real stump landings and scaffold descent before cutting the retained foundation.
The opt-in client smoke generates vanilla oak and
birch trees in an isolated world and verifies real rendered walking/chopping, full deposit, no leftover
supports and no late wood pickup. It writes a local result and screenshot under `run-lumberjack-smoke/`
and exits the client automatically. Its test driver is not shipped in the mod JAR.
The `dirt` and `wood` probes add a third nine-log trunk with a crown: one supplies dirt, the
other requires earned wood for supports. Both require all 20 original logs in the chest,
complete scaffold removal and 40 post-completion ticks without late wood pickup.
A passing test is not a guarantee for arbitrary trees, modpacks, or terrain.

`runClientConfigSmoke` opens both Forge logos and the Core config screen, saves changes
through actual server packets, verifies independent save settings and reload, and completes
a two-log lumberjack job with no axe. Its worlds/results stay in `run-config-smoke/` and the
drivers are excluded from the mod JARs. `runClientForestRepairSmoke -PforestSnapshot=<path>`
is an optional replay of the original three-NPC regression scene; it requires that captured
world as input and copies it into `run-forest-repair/`. The captured save is not distributed.

Add-ons can validate a candidate JSON document without activating it through
[`BehaviorPackValidationApi.validateCandidate(json)`](src/main/kotlin/io/samcnpc/behavior/api/BehaviorPackValidationApi.kt).
New executable actions belong in Kotlin's registered handlers, not in pack data.

## Project status and license

Development version **0.1.0**. This repository publishes Behavior and pins its Core dependency;
it does not include the optional LLM module. API compatibility and advanced gameplay cases remain
development work. No external model can bypass pack validation or the Core action boundary.

[MIT](LICENSE). A community project, not officially affiliated with Mojang or Microsoft.
