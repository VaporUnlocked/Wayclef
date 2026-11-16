# Wayclef validation

Entries from before the rename use the original Soprano name and command syntax.

## Wayclef 0.9.0 beta 7 rename

All 39 automated suites passed. The release JAR contains the Wayclef mod ID, mixin configuration, and bundled planner fonts under the `wayclef` namespace.

Hidden, muted launches of the release JAR in standalone Fabric and Meteor confirmed that `.wayclef settings` and `.way settings` open the Wayclef planner, that the planner loads its relocated font, and that the old command names are absent. Both hosts loaded a custom planner key saved before the rename. Meteor also recovered a custom route-to-crosshair key from settings saved under the `soprano-navigation` module name.

## 0.9.0 beta 7 portals, travel, and survival jobs

All 39 automated suites passed without opening Minecraft windows against Minecraft 26.3 and Meteor 26.3-17.

A visible Meteor client joined a separate vanilla 26.3 LAN host with 150 ms added round-trip latency. The client marked the Overworld portal at `344 64 100`, displayed the Nether projection `43 64 12`, crossed through the portal, and saved the actual Nether aperture at `45 70 13`. The same run inserted two Eyes of Ender in an incomplete frame and threw one ender pearl through an End gateway to its server-configured exit. The portal tests also cover directed links, world isolation, and insertion of an observed crossing into a trip.

Separate runs used a Meteor guest, a vanilla 26.3 LAN host, and 150 ms of added round-trip latency. One Auto stop walked to a boat, boarded, crossed a water channel, dismounted, and continued on foot to the requested destination. Pearl and chorus escapes each teleported the player, used one item, and restored the selected hotbar slot.

The host confirmed a batch of three Long Splash Potions of Swiftness through all four brewing stages, three quartz from a custom brewing recipe using the stand's remaining fuel, one emerald from a locked custom crafting recipe, and an Efficiency I enchanted book. The custom recipes used installed client recipe data and matching server datapacks.

Gather started without a mining tool and crafted wooden and stone pickaxes to obtain raw iron. The host confirmed one bell from the wheat-to-emerald-to-bell trade, with one wheat consumed, and a calf from feeding two adult cows. The host's saved inventory confirmed eight gravel from a vanilla piglin barter and four gold ingots spent across four rolls. The gravel landed in the main inventory while the hotbar was full.

Soprano observed two real Eye of Ender flights, triangulated the generated stronghold at `1584 256`, and saved the waypoint. The estimate matched the coordinates reported independently by the host's structure search. The follow-up live runs used hidden game windows and OpenAL's No Output device.

## 0.9.0 beta 6 survival automation

All 34 automated suites passed against Minecraft 26.3 and Meteor 26.3-17, including Vulkan on the RTX 5070. The packaged JAR matches the one used in the final visible cushion and portal run.

On the separate vanilla host with 150 ms added round-trip latency, the visible client selected a blue cushion over a closer red cushion and reached a designated purple cushion in four right-click hops with Meteor Freecam active. The route took 18 game ticks, about 0.9 seconds. The client sent one click per hop, sending again in the tick when it confirmed the previous seat change.

The same scenario built a 13-obsidian Nether frame on flat ground using temporary dirt steps, removed the steps, lit the frame with hotbar flint and steel, and restored the selected slot. The host confirmed the portal blocks and Soprano added the new map marker. The fixture's window-focus shim let the run continue while other desktop windows were active.

A visible Meteor client joined a separate vanilla Minecraft 26.3 LAN host with 150 ms added round-trip latency. Gather cut four quartz slabs from two blocks with both relevant recipes locked. It used an existing cutter in one run and placed and used a carried cutter in another. The host also confirmed a diamond pickaxe was upgraded to netherite through the smithing table.

Separate visible runs with the same round-trip delay confirmed that Gather cooked four chicken together on a lit campfire and collected the dropped output, and that Gather crafted a fishing rod from three sticks and two string, walked to a dry bank, and caught the requested cod. The campfire fixture accelerated the host to 60 TPS. The fishing fixture used the default game speed and a natural bite.

In visible Meteor single-player, a boat rounded a blue-ice to packed-ice corner at a peak of 0.556 blocks per tick. Auto selected Swim on a separate calm source-water route and reached the goal around an obstacle with full air. These movement checks used the integrated server.

## 0.9.0 beta 5 portal discovery

All 32 automated suites passed against Minecraft 26.3 and Meteor 26.3-17, including Vulkan on the RTX 5070. The portal suite covers aperture normalization, unloaded chunk boundaries, marker persistence, and server, named-world, and dimension isolation.

A visible Meteor client joined a separate vanilla 26.3 LAN host over TCP and found one marker each for an ignited Nether portal spanning two chunks, an active End portal, and an End gateway. It reached a safe stance next to the Nether portal using the same action as Waypoints → Go near. The guest retained markers out of view and reloaded them from disk after switching between two hosted world names in the same dimension. It removed the Nether marker once the changed chunks returned and removed an End portal marker when the host changed the loaded blocks.

This run exercised portal discovery, storage, and approach on a vanilla LAN host. Interdimensional crossings were outside the test.

## 0.9.0 beta 4 multiplayer

All 31 automated suites passed against Minecraft 26.3 and Meteor 26.3-17, including Vulkan on the RTX 5070. A separate recipe index check decoded a tagged vanilla furnace recipe and a mod recipe whose nested filename differed from its actual output.

A visible Meteor client joined a separate vanilla 26.3 LAN host over TCP. The host had no Soprano installation, and the guest had no integrated server. With 75 ms of added delay each way, both carried and preloaded coal runs gathered oak while the host confirmed that three sand were cooking. Both runs crafted locked oak slabs and the locked daylight detector and made one return for the glass.

The final JAR passed a second run with the same delay. The host confirmed the route, diamond mining, blueprint placement, wheat harvest and replant, and quarry. Meteor Freecam responded to camera input independently of the player. Gather checked two unknown double chests once each, reused the stock it learned, discarded the cache for the removed chest, filled a stored bucket with lava, and collected beef from a cow. It also collected two glass in one visit from a previously unopened furnace that held the raw input and fuel, with neither carried by the player.

The live tests used a vanilla LAN host. Dedicated server and third-party server configurations remain unverified.

## 0.9.0 beta 3 furnace lookahead and game recipes

All 31 automated suites passed against Minecraft 26.3 and Meteor 26.3-17, including the Vulkan tests on the RTX 5070.

Two visible Meteor single-player runs of `.sop gather daylight_detector 1` used the normal 20 TPS game clock. The player loaded three sand, left the furnace running, and harvested a real oak log about 16 blocks away while the server confirmed the furnace was lit with sand still inside. The job crafted oak slabs during the cook, reopened the furnace once to collect three glass, and crafted the daylight detector. Both the slab and detector recipes were absent from the recipe book until their outputs were collected. One run used carried coal and one used coal already in the furnace.

A visible `.sop gather lantern 4` run supplied four raw iron, coal, and an iron axe. Gather staged all four raw iron, mined the oak and crafted four torches while the iron cooked, reopened the furnace once to collect four ingots, and finished with four lanterns. This also checks the full-batch recipe ranking that previously attempted to obtain 32 iron tools for their nuggets. Dedicated multiplayer recipe execution remains unverified.

## 0.9.0 beta 2 job timing

All 31 automated suites passed against Minecraft 26.3 and Meteor 26.3-17, including the Vulkan tests on the RTX 5070.

The visible Meteor quarry fixture mined the same seven stone blocks at normal game speed in both 0.9.0 betas. It used an unenchanted diamond pickaxe, mined the upper layer first, preserved the stone just outside the region, and restored the selected slot. The median gap from the server's confirmation to starting the next reachable block dropped from 8 client ticks to 3, about 400 ms to 150 ms at 20 ticks per second. The check measured six transitions using the client's start and stop packets and the server's block update acknowledgments.

Two visible Meteor glass orders each mined eight sand blocks before opening the furnace, loaded all eight at once, and collected eight glass after just one opening. One order used carried coal and the other used coal already in the furnace. Both used a 60 TPS test-world clock to shorten the run.

## 0.9.0 beta 1 planner

All 31 automated suites passed against Minecraft 26.3, including Vulkan on the RTX 5070. The visible Meteor run opened Map by default each time, searched the creative catalog, edited basket quantities, filtered Mine to blocks, and started a Gather job that picked up sixteen torches. It also walked to entered coordinates, followed a cow, saved and found a waypoint, read vanilla stats, ran and recalled a Help command, switched to Light, and reopened Map using a rebound key.

The visible standalone Fabric run discovered an actual MCEdit file, displayed its footprint and carried-material count, moved the plan three blocks, opened the same plan in the map editor, and placed the block with **Build here**. Both hosts loaded the bundled planner fonts. Captures at GUI scales 2 and 3 were reviewed after replacing the fractional heading transforms and matching the font raster to the GUI scale.

## Beta 5 conditional drops

All 31 automated suites, including native Vulkan, passed on the beta 5 release build. A visible Meteor check held the forward key for Freecam while Soprano walked and mined the ore with the real player. The camera moved independently and the mining job completed. The same run completed construction, harvest and replant, tunneling, MCEdit import, Replace, excavation, pickup, and fully buried ore while Freecam was active. It filled a lava bucket from a source using a bucket from storage, crafted a bucket for water, gathered oak and birch planks and beef, and captured a live pufferfish and salmon. The birch log was mined from a diagonal approach that previously timed out because the two ray checks entered through different faces.

A visible Meteor single-player test placed one white sheep closer than two black sheep. With a sword only, `.sop gather black_wool 1` killed one black sheep and left the other sheep unharmed. In a fresh flock with both a sword and shears available, it sheared one black sheep for black wool and later the white sheep for white wool. All three sheep remained alive and unharmed.

A separate visible Easy world with natural spawning disabled verified that `.sop gather slime_ball 1` collected the actual drop after defeating a tiny slime.

The entity condition reader uses Minecraft's codecs for client-visible states, including the color and sheared state in the sheep death and shearing tables and the variant fields in the mooshroom shearing table. The loot scanner follows nested tables and tagged entries. It evaluates the planned direct melee attack when a drop depends on the attacker or damage source.

## Beta 4 gathering and route planning

All 31 automated suites passed against Minecraft 26.3 and Meteor 26.3-17, including the new storage checks for joined chests and copied furnace state. Native Vulkan checks passed on the RTX 5070.

In visible Meteor single-player runs, Gather indexed two double chests while idle and opened the stocked one once with no held tool. It put the emerald in the hotbar. It waited for charcoal using carried logs as both ingredient and fuel, then repeated the recipe with one carried log and coal already inside a different furnace. It collected both charcoal results to the hotbar. Gather crafted two tables from loose logs while keeping the placed table intact.

The hunting and digging run traveled 39 blocks to a cow, took it down in two sword swings, skipped empty chests, and restored the selected slot. It collected all ten dirt from the top of a three-layer strip without descending or touching the lower layers. For cooked porkchop it used a weaker Fire Aspect sword from the backpack, collected cooked porkchop directly, and restored the original sword, hotbar slot, and backpack positions.

A controlled walking fixture compared the previous Soprano search with the new direct walk check and increased search work per tick. The three routes kept the same route costs. These timings measure planning only, with 200 runs for each open route and 30 for the wall detour.

| Route | Median planning time, previous → beta 4 | Median planning ticks, previous → beta 4 |
| --- | ---: | ---: |
| Straight, 94 blocks | 0.929 ms → 0.143 ms | 3 → 1 |
| Diagonal, 94 by 70 blocks | 0.624 ms → 0.219 ms | 3 → 1 |
| Flat wall detour | 5.789 ms → 4.952 ms | 24 → 9 |

Multiplayer inventory behavior and head-to-head route timing with Baritone remain unverified for beta 4.

## Beta 2 automation and Freecam

All 30 automated suites passed against Minecraft 26.3 and Meteor 26.3-17. Vulkan map traversal, route parity, and compute dispatch/readback passed on the RTX 5070. The updated job tests cover history round trips for older region jobs and new fixed-origin nearby jobs. The schematic suite covers numeric IDs, block metadata, offsets, and unmapped blocks in MCEdit files.

Chat commands were exercised in isolated standalone Fabric and Meteor single-player worlds. The player walked to and mined ore, switched to the correct hotbar tool, restored the original slot, picked up drops, placed a selection and an MCEdit schematic, harvested and replanted wheat, dug a two-block tunnel slice, replaced a block, and excavated a selection. In Meteor, Freecam was active with **Rotate** enabled. Camera movement and yaw changed when driven by camera input during the mining job and stayed independent during the jobs.

The same checks passed with the local Baritone 26.3 JAR installed and Baritone idle in a Peaceful fixture with a stationary test drop. An earlier pickup in the default-difficulty fixture stopped at Soprano's safe-ground check. After the successful Baritone run, Minecraft's shutdown watchdog reported Baritone cache threads still running. Standalone Fabric and Meteor without Baritone exited normally.

Multiplayer server interactions remain unverified for beta 2.

## Beta 1 handoff

The build targets Minecraft 26.3 and JDK 25 with optional Meteor 26.3-17 integration. All 30 registered suites passed against the pinned libraries, including 98 terrain checks, 47 survey cache checks, 8 sprint checks, and native Vulkan tests on an RTX 5070. Schematic coverage uses generated `.schem` and `.litematic` fixtures read as files, including offsets, palettes, partial states, and malformed data.

The importer also resolved all 3,201 occupied states in a Sponge v2 file and all 8 occupied states in a Litematica v6.1 file. [Sponge fixture](https://github.com/PrismarineJS/prismarine-schematic/blob/master/test/schematics/smallhouse1.schem) · [Litematica fixture](https://github.com/SmylerMC/litemapy/blob/master/tests/litematics/valid/Subversion.litematic)

## Approved scope

| Votes | Entry point and implemented behavior | Verification |
| --- | --- | --- |
| 1–2 | **J**, **Navigation**, and map actions request crosshair, column, radius, and line-of-sight goals. | Search and terrain suites exercise goal matching. Key and mouse input need gameplay checks. |
| 4–7 | Map no-go regions, per-route action budgets, walking recovery, and optional door/gate cleanup. | Exclusion and terrain suites exercise persisted zones, swept geometry, and door clearance. World edits and recovery need gameplay checks. |
| 8 | Trip stops select transport. Supported boats and mounts can be boarded through normal interaction. Safe walking handoffs depend on verified footing and a path from the landing or dismount. Elytra takeoff is manual. | Glide, rail, and navigation geometry suites pass. Vehicle handoffs need gameplay checks. |
| 9 | Trip portal stops verify the entry and actual exit dimension and area. Grounded Nether entries and End entries with supported footing are handled. Unsupported drops into portal apertures are refused. | Itinerary suite checks expected arrivals and persistence. Live portal transitions need gameplay checks. |
| 10 | The map distinguishes confirmed walking legs from intended direction. Navigation replans through loaded terrain as it advances. | Search suite checks partial and bounded routes. Multi-chunk travel needs gameplay checks. |
| 11–13 | **Trips** edits stops, waits, and repeat mode. Progress is saved separately. Resume starts from the actual position. | Itinerary suite checks persisted waits, independent drafts, circuits, back-and-forth loops, and older trip files. |
| 14–16 | **Navigation** follows a visible player at a configured distance, returns to a revalidated checkpoint, and can route to a saved map location's column. | Safe-checkpoint and snapshot suites pass. Follow visibility, live return, and named-location routing need gameplay checks. |
| 17 | **Navigation** sets independent rocket and durability reserves. Elytra estimates wear, checks void exits, and commits to a verified diversion until touchdown or revalidation requires another approach. | Reserve and glide suites check forecasts and landing decisions. Live boost, diversion, and End flights need gameplay checks. |
| 18–19 | Map **Coverage** shows loaded, surveyed, and unexplored chunks. Hard hazards and opt-in mob clearance apply to navigation. | Terrain, renderer, and exclusions suites check supporting geometry. Coverage display and moving-mob behavior need gameplay checks. |
| 20 | Minecart routes verify loaded reciprocal rails, boarding reach, and a safe dismount area. | Rail suite checks reciprocal rails, slopes, and curves. Server cart movement needs gameplay checks. |
| 21–25 | **Activities** queues mining, tunneling, layered quarrying, crop harvest/replant, and tree harvesting/replant. | Job spec suite checks job definitions. Drops, survival interactions, and changed terrain need gameplay checks. |
| 26–27 | **Activities** queues recipe crafting and furnace input, fuel, and output handling using normal menus. | Job spec suite checks limits and task definitions. Live crafting and furnace menus need gameplay checks. |
| 28 | **Activities** queues storage transfers using item IDs on signs directly above or in front of recognized containers. Standard physical-slot containers use inventory clicks. AE2 virtual storage has no adapter in this build. | Sign suite checks four-line item IDs and ambiguity. Live inventory synchronization and modded containers need gameplay checks. |
| 29–30 | **Activities** queues filtered drop collection and multi-step jobs with per-step limits. | Job spec suite checks encoded definitions and limits. Live collection and chained tasks need gameplay checks. |
| 31–32 | **Build → Plan** imports schematics and exports a loaded selection as `.sopplan`. | Schematic and build suites check decoding, state fidelity, and round trips. Live selection export needs gameplay checks. |
| 33–35 | **Build** executes a blueprint, compares it to the loaded world, and counts inventory and sampled storage. | Build and placement suites check state matching and material counts. Placement, comparison colors, and sampled menus need gameplay checks. |
| 36–37 | **Build** rotates, mirrors, aligns, and repeats flat patterns. | Blueprint suites check transforms and tiled patterns. |
| 38–40 | **Build → Plan** manages owned scaffolds, exact Y layers, and state-aware placement. Unsafe cleanup and unsupported upper-half placements pause. | Scaffold, layer, and placement suites check ownership, access decisions, layer order, and state behavior. Live confirmation and cleanup need gameplay checks. |
| 43–46 | Map actions, job status and steps, route inspection, and the travel HUD are wired to the planner and active controllers. | Source and pinned API compilation checks pass. On-screen behavior needs gameplay checks. |
| 50 | **History** captures routes, follows, builds, queues, and trips. Review is world-scoped. Rerun is explicit and dimension-checked. | History and itinerary suites check atomic definitions, scope, and reopening. UI rerun needs gameplay checks. |

Remote server scope uses the server address and advertised dimension. Client-visible data cannot distinguish plugin sub-worlds that reuse both identifiers.

## Restarted Batch 1 and reported issues

| Votes or request | Entry point and behavior | Verification |
| --- | --- | --- |
| 57–59 | **Navigation** offers observed block IDs, bounded heights, and fixed-heading goals. The final observed block is checked live. | Route goal tests verify goal matching and the original height search limit. Observations are session-scoped. |
| 60–61 | Map A–B selections define preferred walking corridors or hard route bounds. | Tests check weighted walking costs, forbidden edges, and whole-body A–B containment. |
| 63–65 | **Strider** crosses level source lava to verified dry footing. **Navigation** sets health and hunger minimums. **Terrain** enables one-block gap jumps. | Strider geometry, vitality, and jump clearance checks pass. Strider steering and manual dismount require gameplay verification. |
| 66–67 | Map markers record death and recognized respawn-point changes. Pick two surveyed floors to preview a route. | Map tests exercise marker scope and confirmed and partial survey paths. Old snapshots render and can be refreshed to capture safe floor metadata for preview. |
| 69–70, 73, 79 | Click an elevation chart to focus the map, write local notes, isolate lower and upper Y cuts, and show patterns beyond color. | Map tests cover profile discontinuities, scoped notes, render/pick alignment, and patterned overlays. |
| 74–75 | **Profiles** selects global, server, or named-world defaults and saves movement presets. Per-run action budgets are separate. | Preferences tests verify scoped inheritance, preset writes, isolation, and backward-compatible history. |
| 77–78 | **Planning** chooses a fixed or adaptive work budget. **Display** sets distinct arrival, pause, and input sounds and volume. | Budget tests exercise bounded adaptation. In-game audio playback remains open. |
| 80 | The Meteor host pauses Soprano when a recognized movement module is active. | Host linkage and pinned APIs checked. Live conflicts remain open. |
| Corners, slabs, and stairs | Walk edges preserve motion through verified turns and expected height transitions. | Terrain tests cover bounded corner smoothing, yaw-relative movement, and slab and stair descent. |
| Follow pauses | Follow plans from a future verified waypoint while keeping the active route. | Splice tests cover connected continuations, stale candidates, and skipped retracing. |
| Retained surveys | Reopening the map uses the current capture. Disk tiles survive recentering and are scoped by server, world, and dimension. | Cache tests cover overlapping writes, material remapping, world isolation, and deliberate clearing. |
| Dedicated GPU | Vulkan handles empty-space traversal and survey route graph search. Compatible live walking routes can evaluate a GPU candidate, with live footing and edge checks before use. Optimal requires a matching or better cost than its remaining search lower bound. | Native compute, map raster/pick parity, and survey route parity ran on an RTX 5070. Terrain fixtures check candidate validation and preservation of a strictly cheaper Optimal route. |

## Beta 2 movement and edits

| Change | Verification |
| --- | --- |
| Walking carries through verified tight turns, floor-height changes, and confirmed jump landings. | Terrain fixtures check the turn handoff, route clearance, and movement settling. |
| Follow updates the target column every client tick, resolves loaded safe footing below it, and uses that floor for its stopping distance. | Terrain fixtures check airborne targets, slabs and stairs, height transitions, lava, void, and unloaded columns. |
| Sprint uses normal input on verified level runways. It releases before precision movement and obstructions. | Sprint policy checks cover runway clearance, heading, hunger, and input release. The hook signatures were checked against the pinned client. |
| Ordinary routes default to verified one-block jumps, 16 breaks, and 16 placements. Edits verify a local walk edge and wait for server acknowledgement. | Terrain fixtures check two-block clearance, remaining edit budgets, hotbar support placement geometry, and rejected bridge approaches. |

Standalone classes link with Meteor absent. Multiplayer and live movement verification remain open.

## Brainstormer batch 82–100

| Approved | Behavior | Automated verification |
| --- | --- | --- |
| 82, 86, 87 | Find a safe surface route, operate wooden trapdoors, and traverse existing bubble elevators with verified dry access. | Route goal and terrain suites, including blocked or broken shafts, reverse flow, and trapdoor access. |
| 83, 94 | Survey a selection on foot from reachable positions. Cache indexed compressed columns per server, world, and dimension. The optional disk cap defaults to unlimited. | Survey cache and snapshot suites cover legacy data, selective reads, partial corruption, scoped limits, and cached coverage. |
| 84, 85, 96 | Protect route blocks by ID or tag, keep a per-material support reserve, and pause before mining through a tool's durability reserve. | Placement suite covers IDs, tag membership, inventory and offhand counts, and the exact durability floor. |
| 88 | Save a trip cursor before a temporary detour and wait for explicit resume. | Itinerary persistence suite and pinned client compile. |
| 90, 91, 93 | Edit A/B corners and faces, save scoped named regions, and see Nether or Overworld equivalents. | Map features suite covers selections, scoped regions, and negative coordinate conversion. |
| 95, 98 | Preview and apply state-aware blueprint material swaps. Check queue prerequisites before starting. | Blueprint and job suites plus pinned client compile. |
| 99, 100 | Bind a stop key in Display. The default is unbound. Copy and paste movement presets at the bottom left of the map. | Movement profile suite covers transfer validation and compatibility. Both key paths compile against the pinned client. |

All 30 suites passed with Java 25, Minecraft 26.3, Meteor 26.3-17, and matching LWJGL 3.4.3 Java libraries and Windows natives. Vulkan air traversal, route parity, and compute dispatch/readback ran on an NVIDIA GeForce RTX 5070. The cache suite intentionally reads a corrupted column to verify fault isolation. Live Minecraft gameplay and multiplayer server interactions were not tested for this beta.
