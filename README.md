# AutoLumber 2.2 (Fabric, Minecraft Java 26.3)

Client-side tree chopping assistant. Finds the best nearby tree, walks there smoothly, clears leaves, chops with your axe, eats when hungry, replants saplings, swaps axes before one breaks and picks up the drops.

## Install
1. Install Fabric Loader 0.19.5+ for Minecraft 26.3 and Fabric API.
2. Put `autolumber-2.2.0.jar` in your `mods` folder.

## Controls
- **K** – start / stop
- **Right Control** – open the control panel
(rebind both in Options > Controls > AutoLumber)

## Features
- **Smooth, player-like movement:** the camera turns every frame with eased, spring-smoothed motion (no snapping), with a faint natural hand drift. Paths are walked in straight lines instead of block-by-block zig-zags, it eases off before stopping, and it starts looking up at the tree as it arrives.
- **Smarter tree finding:** scans the blocks your game has already loaded a little at a time (no lag spikes), nearest first, and remembers what it found. Trees are ranked by real walking distance, tree size, whether you can see them and how far you'd need to turn. Trees near other players are skipped. When nothing is around it explores toward areas it hasn't visited.
- **No tree climbing:** it never stands on leaves or logs, so it only chops what it can reach from the ground. Unreachable logs (tops of tall trees, logs in the canopy) are skipped.
- **Drops stuck on leaves:** if a dropped log or sapling is resting on leaves out of reach, it breaks the leaf underneath so the item falls, then picks it up.
- **Cliff safety:** paths avoid standing next to big drops, steep climbs cost more than flat ground, and it stops and re-plans if the ground ahead drops away.
- **Auto eat:** eats the best safe food when your hunger drops to your setting (skips rotten flesh, raw chicken, spider eyes, pufferfish, chorus fruit, golden apples). Stops if you're starving with no food.
- **Replanting:** plants a matching sapling where the tree stood (not for dark oak, pale oak or nether trees).
- **Axe care:** stops before your axe breaks or swaps to the freshest axe you have.
- **Pickup:** collects logs, saplings and apples after each tree.
- **Log types:** Oak, Spruce, Birch, Jungle, Acacia, Dark Oak, Mangrove, Cherry, Pale Oak, Poplar, Crimson, Warped.
- **Safety stops:** opening any menu (chat, inventory, pause, this panel), taking damage, hostile mobs nearby, death, disconnect, full inventory.
- **Control panel:** tabs for Overview (live stats), Chopping, Log Types, Survival and Movement.
- **HUD:** animated top-right panel with status, logs, trees, logs per hour (rolling 10-minute rate), axe durability, hunger and session time. Moves down automatically when potion effect icons are showing. Stats reset to 0 each time you start.

## Notes
- Keep the game window focused (Minecraft only mines while the mouse is captured).
- It skips logs it can't reach from the ground (tops of very tall trees).
- Many servers ban any automation, however human it looks. Check a server's rules first.

Settings are saved in `config/autolumber.json`.
