# AutoSell

Client-side Fabric mod for **Minecraft 26.2**. Triple-press a key to loot a chest,
verify your inventory is clean, run `/sellall inventory`, and log the profit.

## What one triple-press does

1. **Opens the chest** — uses an already-open container if there is one,
   otherwise right-clicks whatever chest is in your crosshair.
2. **Loots only what is whitelisted** — shift-click equivalent, one slot at a
   time. Anything not on the sell-list stays in the chest.
3. **Verifies storage** — if anything unlisted is in your main inventory, the
   run aborts, nothing is sold, and you get a client-side note naming the slot
   and item plus your dump-chest coordinates.
4. **Sells** — sends `/sellall inventory`.
5. **Logs profit** — parses the confirmation, prints `+$X (N items) | session: $Y`,
   updates the HUD, appends to `config/autosell-log.csv`.
6. **Loops** until the chest holds nothing else on the sell-list.

## Slot policy

| Region | Treatment |
| --- | --- |
| Hotbar (0-8) | Not checked for trash, but **is** emptied by a dump. |
| Armor + offhand | Always protected (equipment slots). |
| Main storage (9-35) | Checked. Anything unlisted here blocks the run. |

A dump deposits **everything except keep-list gear**, hotbar included.

Your pickaxe, sword and food live in the hotbar, so they never trip the check.

## First-time setup

1. Hold the item you sell and run `/autosell add`.
2. Look at your trash-dump chest and run `/autosell dump set`.
3. Look at your loot chest and triple-press **1**.

## Commands

All client-side; none of these reach the server.

| Command | Effect |
| --- | --- |
| `/autosell` | Open the settings screen |
| `/autosell add` / `remove` | Add/remove the held item on the sell-list |
| `/autosell list` / `clear` | Show or empty the sell-list |
| `/autosell check` | Dry-run the inventory check |
| `/autosell dump set` / `clear` | Set the dump chest to the block you are looking at |
| `/autosell key <glfw>` | Set trigger key (49 = `1`, 50 = `2`) |
| `/autosell window <ms>` | Triple-press window (default 600) |
| `/autosell delay <ticks>` | Delay between container clicks (default 2) |
| `/autosell command <cmd>` | Change the sell command |
| `/autosell regex <pattern>` | Change the confirmation pattern |
| `/autosell loop` / `hud` | Toggle looping / the HUD |
| `/autosell stats` / `reset` | Session profit |
| `/autosell stop` | Abort a run in progress |

## Config

`config/autosell.json`, `config/autosell-log.csv`.

Default sell pattern, matching PlanetPVP's
`ᴘʟᴀɴᴇᴛ ᴘᴠᴘ ▶ You have successfully sold 2304 items for $23,040.00`:

```
sold\s+([\d,]+)\s+items?\s+for\s+\$([\d,]+(?:\.\d+)?)
```

Group 1 is the item count, group 2 the money. Formatting is stripped before
matching, so colour codes and the small-caps prefix do not matter.


## Fuel cycle (`/autosell fuel`)

Buys bones, crafts bone meal, drops it on your farm, repeats.

Per cycle: walk to dump chest -> drain everything but gear -> `/shop` ->
category -> bone -> buy stacks -> size the purchase to free space -> buy ->
walk to farm spot -> craft bone meal -> drop it all -> repeat.

**Purchase size** is computed live: `free slots - reserve` (reserve defaults to
6). Crafting a bone stack frees its own slot and fills three, a net cost of two,
so six free slots converts three stacks before it needs to drop.

**Money math:** bone $6.00, 1 bone -> 3 bone meal, so bone meal costs $2.00
each. One bone stack = $384 -> 192 bone meal.

### Recording the shop path

The shop path is **recorded, not configured**. PlanetPVP labels its UI in
Unicode small capitals - `MOBS` is really `\u1D0D\u1D0F\u0299\uA731`, four
codepoints that are not ASCII `M`,`O`,`B`,`S` and that a player cannot type into
a config field. Slot indices were tried first and were worse: indices read off a
screenshot resolved to obsidian instead of bone.

So: `/autosell fuel record`, then click category -> item -> buy stacks ->
purchase by hand once. Each click stores the screen title, slot, item id and
exact label into `config/autosell-shoppath.json`. The **last** click recorded is
treated as the purchase button.

On replay each step resolves by recorded slot *if the item there still matches*,
otherwise by label search, and aborts if neither matches. `TextFold` folds small
capitals to ASCII so labels still compare correctly.

### Safety rails

Shop slot indices were read off screenshots, so the mod refuses to click blind:

- **Price guard** - the confirm button's own price text is parsed and compared
  against `fuelMaxSpend` (default $20,000). Over the cap, it aborts.
- **Unreadable price = no click.** If no `$` amount can be found on the confirm
  button, it aborts rather than guessing.
- **Money floor** - stops before a purchase that would drop you below it.
- **Dry run** - `/autosell fuel test` walks the entire shop path, reports every
  slot it resolves and the exact price, and stops *without* buying.
- **Layout checks** - every configured slot is bounds-checked against the open
  screen and must be non-empty, so a changed shop layout aborts instead of
  clicking something random.

### Fuel commands

| Command | Effect |
| --- | --- |
| `/autosell fuel record` | Record the shop click path (do this first) |
| `/autosell fuel path` | Show the recorded path |
| `/autosell fuel test` | Dry run - never purchases |
| `/autosell fuel` | Run for real |
| `/autosell fuel stop` | Abort |
| `/autosell fuel spot` | Set the farm spot to where you stand |
| `/autosell fuel status` | Show config + live balance reading |
| `/autosell fuel cycles <n>` | Cycle limit |
| `/autosell fuel floor <amount>` | Stop below this balance |
| `/autosell fuel maxspend <amount>` | Per-purchase ceiling |
| `/autosell fuel reserve <slots>` | Crafting headroom (default 6) |

Balance is read passively from the sidebar scoreboard line containing
`BALANCE` - no command is sent to query it.

## Walking

**Baritone does the walking when it is installed.** Hand-rolled pathing kept
getting wedged; Baritone is a mature implementation that handles doors, gaps,
water, and rerouting properly.

- Built from the upstream `26.2` branch, which targets exactly this setup:
  `minecraft_version=26.2`, `fabric_version=0.19.3`, `java_version=25`.
- Installed as a **separate mod**, not bundled. Baritone is LGPL-3.0; keeping it
  a separate runtime dependency means none of its licensing reaches this mod.
- Reached purely by **reflection** (`BaritoneWalker`), so this mod compiles and
  runs without it and there is no link against LGPL code at all.
- If Baritone is absent or its API fails to resolve, the built-in walker below
  is used automatically. The HUD shows which is active: `[baritone]`,
  `[route N]`, or `[direct]`.

Note: upstream's `:fabric:build` also runs ProGuard, which needs a full JDK with
`jmods`. The launcher ships a JRE bundle, so build with `-x proguard -x test`;
the resulting `baritone-fabric-1.18.0.jar` is a complete, working mod.

## Built-in walker (fallback)

`PathFinder` solves a short-range A* route over the block grid; `Walker`
follows it waypoint by waypoint. A straight line cannot get past a wall however
short the distance, which is why the route is solved rather than steered.

- Search is bounded (48-block radius, 6000 nodes) - these are hops around a
  base, not cross-country travel.
- Water is passable but costs more per step, since it is slow and pushes.
- Steps up 1 block, drops up to 3.
- A chest is not standable, so the goal snaps to the nearest standable tile
  beside it.
- No route found -> falls back to heading straight, which is fine on open
  ground.
- Being shoved off course re-solves rather than giving up: `STUCK` only fires
  after a re-path also fails to make progress.

### Smoothness (fallback walker)

Three things made the first version jerky, all addressed:

- **Rate-limited turning.** The view eased toward the heading (max 18 deg/tick,
  easing over the last part) instead of snapping to an exact angle every tick.
- **Speed modulation.** `moveVector` magnitude below 1 walks slower, so it
  creeps through sharp turns (0.35), eases near the destination (0.45), and
  only sprints on long straight legs.
- **Jump discipline.** Jumping requires being on the ground with a reason - a
  step up or an actual collision - and has a 10-tick cooldown. Falling no
  longer counts as being stuck, which is what produced mid-air bunny-hopping.

No code was taken from Baritone or similar projects: they are (L)GPL and
copying them would impose that license on this mod. These are standard
techniques, implemented here directly.

### Opening blocks

`BlockOpener` turns to face a block and only interacts once the crosshair is
genuinely on it, verified against the live `hitResult`. Fabricating a
`BlockHitResult` and firing it immediately does not work: the server validates
where the player is looking and silently discards the interaction, which
presents as the character staring into the air next to an unopened chest.

Movement is injected at TAIL of `KeyboardInput.tick()` - vanilla rebuilds both
`keyPresses` and `moveVector` from the keyboard every tick, so a tick-event
write would be overwritten before movement applies.

## Craft and drop loop

Bones are converted in the 2x2 inventory grid, one stack at a time. One bone
stack yields three stacks of meal, so the loop is driven by free space: craft
while there is room for the output, drop everything when there is not, then
resume crafting until no bones remain.

Pathing aims at the nearest **standable** block, not the recorded block: a spot
recorded while straddling two blocks or on an edge can resolve to a position
Baritone reports as unreachable. Alignment then covers the exact sub-block
placement, and if pathing gives up within 6 blocks alignment walks the rest.

`/autosell fuel spot` records the **exact** stand position and facing, not just
the block. Baritone gets to the right block, then `Walker.startAlign` slides
onto the precise point by **strafing** - turning to walk would ruin the throw
angle it just set. Drifting more than 0.35 blocks re-aligns before the next
throw.

Both crafting and dropping re-check distance to the farm spot first, and the
drop also faces the direction saved by `/autosell fuel spot`. Thrown items fly
where the player looks, so position alone does not put them in the collector.

`craftDelayTicks` (default 1) paces this loop separately from container
navigation, so crafting and dropping run essentially flat out.

## Run-scoped state must be reset

A run that succeeded once and wedged the next time is the signature of state
carried over between runs. `start()` was resetting four fields while eleven
others persisted - a stale quantity slot, a pending counter wait, a
half-finished craft flag, watchdog counters, and a `BlockOpener` whose retry
budget was already spent (so it reported failure immediately).

Both routines now have a single `resetState()` that clears every run-scoped
field, called from `start()`, `abort()` and `finish()`. `BlockOpener.stop()`
clears its counters too, and callers use `ensureTargeting()` rather than
skipping `start()` when the target happens to match.

## Debug log

`config/autosell-debug.log` records every phase change with a one-line state
dump (cycle, step, target amount, pending waits, bones/meal counts, what is on
the cursor, which screen is open, which walker is driving), plus a full dump on
any abort or stall. Rotates at 1 MB to `.log.1`.

Failures that only surface on a later run cannot be reconstructed from memory -
by the time anything looks wrong the state that caused it is gone. Toggle with
`/autosell debug`.

## Movement input sign convention

Vanilla builds the move vector as
`new Vec2(calculateImpulse(left, right), calculateImpulse(forward, backward))`,
so **`moveVector.x` is the LEFT impulse** - positive means left, not right.

Fine alignment projected the offset onto the facing and fed the *rightward*
component straight into `x`, so every sideways correction pushed the wrong way.
That turns the controller into positive feedback: it oscillates about the
target, and wedged between blocks it whips on the spot with almost no net
displacement - visible client-side, invisible to the server.

Alignment now negates that component, and is damped so it cannot oscillate even
when the target is not exactly reachable:

- per-axis deadzone (0.04) so a satisfied axis stops contributing
- proportional speed capped at 0.45, floored at 0.12
- accepts `ALIGN_GOOD_ENOUGH` (0.4) after 30 ticks without progress, rather than
  grinding against geometry
- re-aligns only past 0.8 blocks of drift, and at most 3 times per cycle, so a
  spot it can only reach approximately does not bounce between states

## One container click per tick (important)

`ServerboundContainerClickPacket` carries a **`stateId`**. Two clicks issued in
the same tick are both stamped with the same id, so the server accepts the
first, advances its state, then treats the second as stale and resyncs the whole
container - undoing it.

Placing an ingredient was doing exactly that: pick up from the inventory and
insert into the grid, both in one tick. The insert was reverted every time, so
the stack bounced between inventory, cursor and grid at about 5 Hz and nothing
was ever crafted. The debug log made it obvious - `bones` alternating by exactly
64 while `meal` never moved.

So the inventory path is strictly **one click per tick**, and each step **waits
for its own effect** (cursor filled, grid occupied, result present) instead of
trusting a delay - which also makes it correct at any ping.

Chest transfers still burst: those loops re-scan the container every tick, so a
rejected click is simply retried on the next pass. The inventory menu has no
such self-healing, which is why it needs the stricter discipline.

## Crafting is server-authoritative (important)

`InventoryMenu.slotsChanged` hands off to
`CraftingMenu.slotChangedCraftingGrid`, which takes a **`ServerLevel`** - so
crafting results are computed **only on the server**. The client's result slot
stays empty until a round-trip lands.

Treating that momentary emptiness as "nothing to craft" made the loop pull the
ingredients straight back out and re-place them: the ingredients visibly
wobbled in and out of the grid, and the pickup/place race stranded a stack on
the cursor.

So an empty result **while the grid still holds ingredients** means *waiting*,
not *finished*. It waits up to `RESULT_TIMEOUT` (2s) and, after three genuine
failures, says the recipe may not exist here rather than spinning.

## Cursor hygiene (important)

`ContainerInput.THROW` is a **no-op whenever the cursor is holding something** -
vanilla checks `getCarried().isEmpty()` and skips the entire drop path
otherwise. A stack stranded on the cursor therefore silently disables dropping
*and* blocks deposits into the crafting grid, which presents as the routine
sitting there doing nothing.

The stack got stranded because placing fired pickup-then-place in one tick; if
the grid still held a full stack, the deposit could not happen and the cursor
kept its contents. So before every craft or drop:

1. If the cursor holds anything, it is merged onto a matching stack, put into an
   empty slot, or failing both, thrown out.
2. If the crafting grid is occupied, it is emptied first.

A watchdog measures actual progress (bones, meal, carried count) and aborts with
a message after 10s without change, so a stall can never again look like a
finished run.

## Amount buttons

The shop's quantity buttons are labelled `+1`, `+16`, `+32`, `-1`, `-16`, so
they are **discovered by reading those labels** rather than recorded. A greedy
largest-first walk reaches the target, and the `+1` button means any amount is
reachable exactly. This replaced replaying recorded clicks, which hardcoded
whatever amount happened to be clicked while recording.

## Burst transfers

Vanilla's shift+double-click is not a bulk packet: `AbstractContainerScreen`
loops over every slot in the container and fires one `QUICK_MOVE` per matching
slot, all inside a single tick. `burstLoot` reproduces exactly that, capped by
`burstMaxClicks`. (`PICKUP_ALL` is the *non*-shift double-click and only
gathers one stack onto the cursor, so it is not used.)

## Inspecting server GUIs

Chat is unreachable while a container has focus, so GUIs are logged
**automatically**: every server screen that opens appends itself to
`config/autosell-guidump.txt`. The `P` key snapshots the focused GUI on demand.

`/autosell inspect` dumps every filled slot of the open container - index,
row/col, item id, display name and lore - to chat and to
`config/autosell-guidump.txt`. Use it to verify any shop click path before
trusting it with money.

## Building

Requires JDK 25 (the Prism instance ships one):

```
export JAVA_HOME=~/Library/Application\ Support/ElyPrismLauncher/java/java-runtime-epsilon
./gradlew build
```

Output: `build/libs/autosell-1.0.0.jar`.

## Notes on 26.2

26.2 ships **deobfuscated** — Yarn and intermediary both stop at 1.21.11 and
Mojang publishes no `client_mappings` for it, so `build.gradle` intentionally has
**no `mappings` dependency**. Renames that affect this mod:

- `ClickType` → `ContainerInput`
- `handleInventoryMouseClick` → `handleContainerInput`
- `mc.screen` → `mc.gui.screen()`
- `ResourceLocation` → `Identifier`, `ResourceKey.location()` → `.identifier()`
- `GuiGraphics` → `GuiGraphicsExtractor`; `render` → `extractRenderState`
- `KeyboardHandler.keyPress` now takes a `KeyEvent` record

The trigger is a `KeyboardHandler` mixin rather than a `KeyMapping`, because
container screens swallow number keys for hotbar swaps — a normal keybind would
never fire while a chest is open. It observes only and never cancels, so hotbar
switching still works. The trigger is ignored unless you are in-world or have a
container open, so typing `111` in chat does nothing.
