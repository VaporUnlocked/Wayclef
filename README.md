# Wayclef 0.9.0 beta 7

Wayclef is a client-side navigation, construction, and automation mod for Minecraft 26.3. It runs standalone on Fabric and as a Meteor addon. It uses ordinary Minecraft movement and interaction on terrain the client has loaded.

## Install

Install `wayclef-0.9.0-beta.7-mc26.3.jar` in the instance's `mods` directory. If you're upgrading from Soprano, replace its JAR with Wayclef in the same instance. This build uses Minecraft 26.3, Fabric Loader 0.19.5 or newer, and Java 25 or newer. Meteor integration targets Meteor 26.3-17. Wayclef runs on the player's client and uses normal multiplayer interactions.

Wayclef reads your existing preferences, waypoints, trips, and other saved data from the instance's `soprano` folder. Existing `.sopplan` files still open in the planner. Meteor also loads settings saved under the old Soprano module name.

Press the grave/tilde key (below Esc on a US keyboard), run `.wayclef settings`, or use the Wayclef launcher in Meteor. Each opens the planner on **Map**. Change the planner key in **Settings**. Press **J** in-game to route to the crosshair. Change that shortcut in Meteor's Wayclef module settings.

## Planner

- **Get** browses the item stacks and categories exposed by Minecraft's creative tabs, including items added to those tabs by installed mods. Search by name or item ID. Add entries to the basket, edit their quantities, and start a Gather or Mine queue in the current world. Get quantities are target inventory totals. Mine quantities count blocks broken.
- **Go to** takes X and Z with optional Y. Preview a route on the map, start walking, or reopen a recent destination from this world. **Follow** lists nearby players and entities and can track an individual target or the nearest member of a group.
- **Waypoints** adds and opens local map markers. **Schematics** browses the game's `schematics` and `soprano/build-plans` folders and can also open a file by path or drag and drop. Preview the footprint and materials, then build or open the plan in the map editor.
- **Stats** reads Minecraft's player statistics. **Help** lists and runs Wayclef commands. **Settings** has searchable preferences, light and dark themes, and the planner key. **Advanced** opens the map editor, saved surveys, trip and job editors, history, and detailed movement and display forms.

## 3D planning map

The active survey stays in memory when the map closes. Surveyed sections are saved by server, world, and dimension in independently compressed columns. The cache reads the requested columns and reuses unchanged compressed data on updates. It defaults to unlimited storage. Set an explicit cap under **Display** to evict least-recently-used tiles in the current scope. **Refresh** resamples loaded terrain. **Clear surveyed sections** clears the current scope after confirmation. Sections outside the viewport can be reused when you return.

The map surveys loaded terrain within your client view distance. It shows perspective terrain with original 8×8 tiles from block color palettes. Partial blocks use their captured shapes. The viewport lowers its raster resolution while the camera is moving. At rest it refines geometric edges and keeps block picks aligned to the center ray. The survey fills in progressively and can be refreshed after you move or the terrain changes. Covered block materials use a neutral palette. Vanilla entity models appear where the surveyed terrain leaves them visible, and your player has a distinct highlight. Server text holograms are hidden in the map. If the camera enters a solid block, the view cuts through the nearby solid material until it reaches open space.

- Move horizontally with WASD. Space raises the camera straight up, Ctrl lowers it, and holding Shift multiplies movement speed by five. Left drag orbits, right drag pans, and the wheel zooms. The height controls cut the rendered volume at the chosen Y level.
- Press the middle mouse button over the first block, then drag and release over the opposite corner to select a 3D region. In Build, Plane mode sets both corners on the work plane.
- Click a visible cube to inspect it. Use **Preview** to highlight a route in the map or **Walk** to start navigation toward the block's standing surface.
- Set A and B on two cubes or type the corner coordinates. Move individual selection faces and save or recall named regions scoped to the server, world, and dimension. The inspector counts surveyed and unknown voxels and shows a time estimate at the chosen work rate.
- **Survey on foot** checks retained coverage first, then walks to safe positions that can reveal missing terrain. It uses the normal client view distance and saves surveyed sections as it goes. A survey can cover up to 4,096 selected chunk columns. You can retake control with a movement key or stop it from the planner.
- Pick a block in the Overworld or Nether to see the equivalent X/Z in the other dimension. Use the bottom-left **Copy** and **Paste** buttons to transfer movement presets as text between installations. Review imported values before saving the preset.
- Right-click a block for routing, line of sight, saving a location, and inspection. Right-drag still pans. Turn on **Coverage** to see live loaded chunks, previously surveyed chunks, and unexplored chunks around the camera.
- Name the A–B region under **No-go zones** to exclude it from routes. Click its name to show its bounds. Zones are stored per world and dimension. **Terrain** controls doors, gates, wooden trapdoors, and optional mob avoidance. It also accepts protected block IDs or tags and support material and tool durability reserves. Support reserves count each eligible material separately and default to zero. The tool durability reserve starts at one point.
- In **Navigation**, enter feet coordinates or omit Y for a column goal. The arrival radius is configurable. Set a separate break and placement budget for a coordinate route. Use **Return to safe point** to request an earlier verified grounded position. Follow distance is configurable.
- Use **Inspect step** after a route preview to see the move, cost, and alternative outcomes. Confirmed ground is highlighted on the map. Amber dots give the direction beyond the confirmed leg of a staged route. The travel HUD shows the next instruction, confirmed remaining distance, and ETA when available.

| Command | Action |
| --- | --- |
| `.wayclef goto <x> <y> <z>` | Plan a path and walk to the specified feet coordinates. |
| `.wayclef goto <x> <z>` | Walk to the column at a reachable height. |
| `.wayclef here` | Walk to the top of the nearby block under the crosshair. |
| `.wayclef surface` | Walk to a reachable, sky-exposed standing position in loaded terrain. |
| `.wayclef detour` | Suspend the current trip and take a route to the crosshair target. |
| `.wayclef follow <player>` | Walk toward a visible player and maintain a gap. |
| `.wayclef preview <x> <y> <z>` | Show a route before walking. |
| `.wayclef pause`, `.wayclef resume` | Pause walking or replan from your current position. Run Follow again to resume tracking a player. |
| `.wayclef stop` | Release movement and clear the displayed route. |
| `.wayclef status`, `.wayclef settings` | Read the job status or open its settings. |

Use `.wayclef` or `.way` in standalone Fabric. In Meteor, use the configured command prefix with `wayclef` or the short alias `way` when it is free. The `b` alias is also available when Baritone is absent and the alias is free.

## Automation commands

Gather plans ingredients for a recipe batch before starting work at the station. For example, `.way gather glass 8` can collect all eight sand first, load one furnace, and wait for the full batch. It can count usable fuel and matching ingredients already in a known furnace.

If another part of the same order can be done while the furnace cooks, Gather leaves the fueled batch running and works on that part. It returns to pick up the cooked output before using it in a recipe. If it reaches the furnace early, it waits with the menu closed until the batch is expected to finish. Furnace progress depends on the server keeping that area active.

In single-player, Gather plans from the active world's recipe manager. In multiplayer, it uses recipes the server shares and checks available client recipe files by their actual outputs. For supported shaped and shapeless recipes, Gather can fill the grid directly and waits for the server's result. A custom server recipe can be planned when the server shares it or matching recipe data is installed on the client. The server's crafting rules still apply.

**Job lookahead** in **Settings** accepts 1–32 and defaults to 8. It controls how many nearby block targets Gather can group, how many batches Gather can leave cooking, and how many same-layer targets a quarry can check while a block is breaking. The quarry rechecks reach, footing, and its exit before starting the next block.

| Command | Action |
| --- | --- |
| `.wayclef mine [count] <block> [block...]` | Search loaded blocks within 24 blocks of your start on each axis, including buried blocks. Excavate a verified approach when needed. The count applies to target blocks. Omit it to work through the available matches. |
| `.wayclef gather <item> <amount>` | Bring the inventory count up to the requested amount. Gather can pick up drops, mine, hunt, take items from nearby storage, make ingredients and stations, craft, and smelt. |
| `.wayclef build [file]` | Build the current world blueprint or import a schematic at your feet and build it. File paths can be absolute, relative to the game directory, or filenames in `schematics`. |
| `.wayclef build at <x> <y> <z> <file>` | Import and build a schematic with the supplied origin. |
| `.wayclef farm [radius]` | Harvest and replant mature crops. Sugar cane, cactus, and bamboo are cut above the base. Radius defaults to 24 and accepts 1–32. |
| `.wayclef pickup <item[,item]> [count]` | Pick up matching reachable stacks within the nearby work area. When a count is supplied, each targeted stack must fit the remaining count. |
| `.wayclef tunnel [length]` | Dig a straight 1×2 tunnel in the player's facing direction. Length defaults to 16 and accepts 1–128. The tunnel needs a verified floor. |
| `.wayclef sel pos1`, `.wayclef sel pos2` | Mark the block under the crosshair or append explicit `x y z` coordinates. The same selection is available in the planner's region tools. |
| `.wayclef sel fill <block>`, `.wayclef sel shell <block>` | Place a solid or hollow blueprint in the selection. Obstructing blocks are reported. |
| `.wayclef sel cleararea`, `.wayclef sel replace [x y z]` | Excavate the selection or replace exposed cubes matching the sampled block with the replacement block held in your hand. |
| `.wayclef job <step>` | Run an Activities step directly. Use the crosshair for a tunnel destination or station. |

If a mining, farming, or excavation job needs a tool, put a suitable one in the hotbar. Wayclef picks a compatible slot above the durability reserve and restores the selected slot when the break finishes. Open **Activities → Job commands** for tree, crafting, furnace, unload, and restock syntax. Mining, pickup, and crops accept an optional nearby radius in the queue. The HUD shows progress. Use `.wayclef status` to read the latest status or `.wayclef stop` to release control.

Gather uses registry IDs such as `minecraft:bucket`, `minecraft:pufferfish_bucket`, or an installed mod's item ID. It follows known ingredient chains and checks inventory after each action. For `.wayclef gather lava_bucket 1` or `.wayclef gather lava 1`, it uses an empty bucket on hand, checks nearby storage for one, or crafts one. It then fills the bucket from a reachable source. Mob buckets use the appropriate container on a live mob, including standard fish, axolotl, and tadpole buckets. Milk uses an empty bucket and a compatible adult animal.

Gather checks blocks within 24 blocks of the starting point on each axis and looks for drops and mobs in the player's loaded render area. It prefers to harvest the upper surface and avoids mining the blocks the player stands on. It follows a moving mob, picks a carried weapon using damage and attack speed, and can use Fire Aspect when the mob's loot and the cooking recipe support the requested cooked drop. Gather checks loot conditions against the actual mob, including sheep color and sheared state. With usable shears, it prefers shearing the matching mob when a shearing loot table supplies the requested item. Gather fills matching hotbar stacks and open hotbar slots first. It leaves placed crafting tables available for recipes.

Storage discovery runs while idle. Wayclef indexes a double chest as one inventory and remembers the contents of verified block inventories it opens. Single-player can also read supported nearby vanilla inventories from the local server. On multiplayer servers, it learns contents from opened menus. Gather skips known empty storage, checks known stock and loaded world sources first, and limits checks of unvisited storage. Furnaces can use fuel already loaded or burning and Gather waits for the result.

Gather uses available recipes and loot data to find ingredients and outputs. It can batch supported stonecutting recipes, cook up to four items at a time on a nearby lit, empty campfire, and apply fixed-output smithing upgrades. Smithing stages ordinary crafting inventory stacks such as an unenchanted diamond tool with the matching upgrade template and ingot.

For fishing loot such as `.way gather cod 1`, Gather can get or craft a rod, walk to dry footing near a loaded pond, cast, and reel in using the server's bite signal. It checks known biome and open-water requirements. Standard modded recipes, loot, fluid buckets, and mob buckets can work when the needed data is available. Custom machines still need station-specific integration.

When a loaded mining source requires a tool, Gather checks the carried inventory and can plan the recipe for a suitable tool. It can inspect nearby merchants' actual offers and gather their payment. Piglin bartering uses the known barter loot table. Use `.way breed <mob> [count]` to breed eligible nearby adults with carried food.

Aim at a brewing stand and run `.way brew <potion> [amount] [form]`, such as `.way brew long_swiftness 3 splash`. Carry the bottles, reagents, and blaze powder. Use `.way brew item <item> [amount]` for other recipe outputs. Aim at an enchanting table and run `.way enchant <item> [offer] [enchantment]` with the item, lapis, and the required levels. Wayclef checks the server's displayed offer before using it. Standard installed mod recipes can be used when the recipe format and station are supported.

With Meteor Freecam on, Wayclef walks and works from the player's real position. Use your movement keys and mouse to move the detached camera during a job. Freecam's **Rotate** setting yields player aiming while Wayclef controls the player. Chat builds run bottom-up. Set a scaffold limit in **Build → Plan** when a design needs temporary supports.

Set the start and destination on the map to preview a route through surveyed terrain. **Go to destination** requests a live route from your current position. Click the elevation plot to focus that point in 3D. Lower and upper Y cuts isolate a floor. The map also shows the last death, observed respawn point, and local notes. Enable patterned overlays for route and coverage markers that use symbols as well as color.

## Saved locations

Open **Locations**, enter a name, and choose **Save location**. The file records the surveyed terrain, camera, and height cut. Select a saved name to open it and choose **Go to saved location** to route to the saved camera's X/Z column using live terrain. Snapshots are stored in `soprano/locations`.

## Build planner

Open **Build** to design on a 3D blueprint layer. Search the block registry by name or ID, choose a block, and click a face to attach it. Choose **Plane** and set the work Y to place blocks at a fixed elevation. Paint recolors a blueprint block and Erase removes it. Use **Sample** to pick the clicked block material.

- Set A and B to draw lines, solid or hollow boxes, or solid or hollow cylinders. The shape preview follows the pointer and updates as you choose a block or adjust the corners. Press Enter or choose **Apply** to commit the shape as one undoable edit.
- Copy a region, paste it at a new origin, rotate it 90°, mirror it across X or Z, or repeat a flat clipboard pattern across a plane. Undo or redo with Ctrl+Z or Ctrl+Y.
- Open **Plan** to import an MCEdit `.schematic`, Sponge `.schem`, or Litematica `.litematic`. Paste an absolute path or a path relative to the game directory. Select A to position the file origin. Older states are migrated with the Minecraft registry when available. Unavailable states remain in the plan and are counted after import.
- **Export A–B** records loaded terrain as a reusable `.sopplan`. **Align to A** moves the occupied minimum corner. **Compare** marks missing, wrong, extra, and misoriented cells. **Save** and **Load** use the game's `soprano/build-plans` folder. A plan holds up to 131,072 blocks.
- **Swap materials** in **Plan** previews a replacement across the blueprint or Build A–B selection, preserves compatible block states, and applies the swap as one undoable edit.
- **Materials** shows required items, inventory, sampled storage, and missing counts. Pick a reachable container on the map and choose **Read picked storage** to read its item slots by opening it in-game.
- **Start construction** uses the chosen one-block layer order and placement limit. Set a scaffold limit above zero to allow temporary placements. Wayclef tracks confirmed job-owned scaffolds and retries eligible cleanup through **Retry scaffold cleanup**. Existing obstructions, missing supplies, unsupported states, and unsafe access are reported. Top-down builds can pause at blocks that vanilla places from their lower half.

Editing and comparing a blueprint changes the preview. Starting construction performs normal movement and placement in the live world.

## Transport routing

Walking routes can use verified upward and downward bubble elevators that have accessible dry entry and exit positions.

Select a mode in **Navigation** or in an individual trip stop. **Auto** recognizes current movement, including a ride, glide, or swim. Preview draws planned transport waypoints in blue.

| Mode | Starting condition | Route behavior |
| --- | --- | --- |
| Elytra | Already gliding | Forecasts durability and rocket use. Low reserves trigger a diversion to a verified reachable landing. Void legs require a confirmed exit. A normal arrival can continue on foot. |
| Boat | Driving a boat or at a supported boarding point | Searches loaded source water and level ice with full hull support. Auto can join a verified walking route from shore. |
| Ground mount | Steering a supported saddled horse, donkey, mule, camel, skeleton horse, or zombie horse, or at a supported boarding point | Uses the mount's clearance and can hand off to walking at a verified dismount point. |
| Minecart | Riding or within reach of a usable cart | Verifies loaded connected rails and a safe dismount area before starting the leg. |
| Strider | Mounted with a warped fungus on a stick in your main hand | Crosses verified loaded source lava at the same level to dry shore. Dismount manually after landing. |
| Swim | Unmounted and already in water | Follows a continuously breathable surface route through calm source water. Choose a destination in the water. |
| Cushion | Seated on a cushion or on dry ground within reach of one | Follows the fewest reachable cushion hops with normal right-clicks. Sends the next click as soon as the server confirms the seat change. |

Use `.way cushion to <x> <y> <z>` to reach a destination seat. Use `.way cushion [color]` to walk to a free seat and sit down, or sneak as usual to dismount.

Set rocket and durability reserves in **Navigation**. Keep usable fireworks available for a diversion. Each trip stop can select its own transport mode. Prepare the glide yourself before an Elytra stop.

An **Auto** trip can stage at a nearby loaded boat or supported mount, board it, and continue on foot after the ride. Use `.way escape [pearl|chorus]` to use a carried teleport item. The pearl option checks a clear throw and dry landing. The chorus fruit option uses Minecraft's random teleport.

**Activities → Check queue** reviews queued regions, tools, recipes, stations, and supplies. Starting a queue rechecks available prerequisites and reports missing requirements before movement.

## Trips and history

Wayclef automatically marks active Nether portals, End portals, and End gateways it finds in loaded chunks. Look for **P** on the map or select **Portals** on the **Waypoints** page. A portal's **Go near** action routes to nearby safe footing. Saved markers are scoped to the server, world, and dimension and remain available after their chunks unload. A loaded portal that disappears is removed from the index. For hosted worlds that reuse the same dimension ID, set the **Hosted world name** in **Profiles**.

Use `.way portal mark [x y z]` to save a portal immediately. A Nether portal's waypoint shows the expected counterpart using the 8:1 X/Z scale and the original Y as a starting estimate. **Enter** or `.way portal enter [x y z]` walks through it. Wayclef records the actual exit it observes, and Trips can use observed crossings to connect stops in different dimensions.

Use `.way portal build <x> <y> <z> <x|z>` to build a 13-obsidian frame. Pick the lower opening cell with the smaller coordinate along the chosen axis. Start with clear space and solid ground under the bottom row. Carry dirt for temporary steps to reach the upper blocks. Use `.way portal light <x> <y> <z>` on the same opening cell, or aim at the top of the bottom obsidian and run `.way portal light`. Keep flint and steel or a fire charge in the hotbar or offhand.

Run `.way stronghold` in the Overworld, throw an Eye of Ender, then move sideways and throw another to estimate the stronghold's X/Z column. Use `.way stronghold mark` to save the estimate or `.way stronghold go` to approach it. Use `.way portal eyes [x y z]` near a complete frame with the remaining eyes in the hotbar or offhand. Use `.way portal gateway [x y z]` or select **Use pearl** on a loaded End gateway waypoint.

Use **Trips** to add or edit stops, set waits, and select Once, Circuit, or Back and forth. Saved trips are scoped to the current world. An unfinished trip can be explicitly resumed after reconnect and replans from your current position. **Detour** saves the itinerary cursor and takes one temporary route. Choose **Resume progress** when you are ready to continue the trip. For a known portal stop, enter its exact walk-in coordinates and `exit-dimension X Y Z [radius]`. Wayclef checks the actual arrival before continuing. Supported End portal entries require solid footing under the aperture.

Open **History** to review a saved definition in the current world. Preview a route, blueprint, queue, or trip. **Rerun task** explicitly starts a new run using the stored definition in its original dimension.

## Replace activity

Mark A and B on the map, select the kind of block to replace, and hold the desired replacement block. Put it in your offhand to mine with a main-hand tool. Open **Activities** and choose **Preview** to check the selection and materials. **Start Replace** begins work using normal movement and block interaction. **Cancel activity** or `.wayclef stop` stops the job. Replace works on reachable, exposed full cubes in loaded terrain, up to 32,768 voxels per selection. The server still validates normal Minecraft block interactions.

The **Activities** queue also accepts mining, item collection, crop, tree, quarry, tunnel, crafting, furnace, storage, and safe return steps. Expand **Job commands** for the exact syntax. Set a region or a picked target before queuing a step that requires one. Storage signs can list an item ID on each of four lines. A recognized sign must be directly above or in front of a supported container. Vanilla and compatible physical-slot menus use normal inventory clicks.

## Settings

| Profile | Behavior |
| --- | --- |
| Cautious | Cardinal walking and normal step changes. |
| Balanced | Adds safe diagonals, one-block jumps, and one-block drops. |
| Explorer | Looks for safe progress when a complete route extends beyond the current search. |
| Custom | Set jump and diagonal rules, maximum drop, search range and budget, and partial routes. |

You can set the search time and work budget per tick, limit replans and partial legs, and select Optimal or Quick search. Quick favors progress toward the goal and can choose a longer route. Display settings include map resolution, WASD camera speed, entity markers, names, and the status and path overlays. The route overlay uses amber for jumps, blue for drops, and purple for climbs. Walking routes account for collision heights on stairs, slabs, and other partial blocks. Ladder connections use normal movement inputs. Settings apply to the next request. The planner checks loaded collision shapes and rejects hazardous footing and unsupported landings. Terrain changes and obstructions can interrupt an active route.

Wayclef retains the bridge that lets Mosaic Studio request a detour when it cannot retrace a saved route. Mosaic Studio 2.0.2 needs an update to recognize Wayclef's `wayclef` mod ID. The return job checks for a complete path to the saved start before walking.

**Navigation** can route to an observed block ID, a reachable height band, or a point along your current heading. Mark preferred corridors on the map or constrain a walking route to the A–B volume. Wayclef carries motion through verified turns and uses normal sprint controls on suitable ground. Follow tracks the live target's X/Z and verified ground height below them while they jump or fall. Health and hunger minimums can pause travel.

Verified one-block parkour jumps are on by default. Ordinary player routes can use up to 16 block breaks and 16 placements by default. Adjust the limits in Navigation, override them for one route, or set a limit to zero to disable that action. Edit planning checks the adjacent route edge, interaction reach, and confirmed world state before walking through it.

**Profiles** saves named movement presets and per-world defaults. Global values apply where the current world has no override. When a hosted server reuses a world identity, choose a world name for that session. **Planning** offers adaptive or fixed budgets. **Display** controls optional arrival, pause, and input-needed sounds. Set **Stop all Wayclef activity** there to cancel movement and interactions with one press. The key starts unbound and takes priority while Wayclef is active. With Meteor, Wayclef pauses for recognized active movement modules.

## Dedicated GPU

**Dedicated GPU compute** is enabled by default in **Display**. A discrete Vulkan device can process empty-space map traversal and survey-route graph search. Compatible live walking requests can run a GPU candidate alongside the regular planner. Minecraft collision and movement checks stay on the client thread. The CPU also resolves precise map materials, selection hits, and depth. Unsupported devices and incompatible requests use the CPU paths.

## Build

Run the build script with a JDK 25, the official Minecraft 26.3 client JAR, the Meteor 26.3-17 JAR, and local Minecraft and Fabric library directories.

```powershell
python tools/build.py --jdk "C:\path\to\jdk-25" --minecraft "C:\path\to\26.3.jar" --meteor "C:\path\to\meteor-client-26.3-17.jar" --libraries "C:\path\to\libraries" --test
```

The script creates `all .jar's go here/wayclef-0.9.0-beta.7-mc26.3.jar`. The `--test-gpu` flag requires a discrete Vulkan device and available LWJGL natives for the GPU integration tests. See `VALIDATION.md` for the beta's verification details.

The planner uses Inter with font raster sizes matched to the in-game GUI scale. The JAR bundles the font and the Fabric resource loader for both supported hosts. [Inter license](https://github.com/google/fonts/blob/main/ofl/inter/OFL.txt)
