# Architecture — program map

27 classes, ~6,400 lines. Everything is client-side; the mod sends only the
packets a player could send by hand.

## Package layout

```
com.narek.autosell
├── AutoSellClient          entrypoint, tick pump, key routing, chat output
├── command/
│   └── AutoSellCommands    every /autosell subcommand (brigadier)
├── config/
│   ├── AutoSellConfig      all settings; seeds from jar defaults
│   └── ConfigScreen        settings GUI
├── core/
│   ├── Aim                 angle maths, rate-limited turning, crosshair check
│   ├── BalanceReader       balance from shop lore, else sidebar scoreboard
│   ├── BaritoneWalker      reflection bridge to Baritone (optional)
│   ├── BlockOpener         look-at-then-interact for chests
│   ├── ContainerInspector  on-demand GUI dump (P key)
│   ├── DebugLog            rolling phase/state log
│   ├── GuiLogger           passive auto-dump of every server GUI
│   ├── InventoryScanner    slot classification: sell / keep / offender
│   ├── LoreReader          item lore as plain text
│   ├── PathFinder          short-range A* fallback
│   ├── SellRoutine         STATE MACHINE 1 — the sell run
│   ├── ShopNavigator       find a GUI entry by folded name
│   ├── TextFold            Unicode small-caps → ASCII
│   ├── TriplePress         N-presses-in-a-window detector
│   └── Walker              movement: Baritone, A*, or fine alignment
├── fuel/
│   ├── FuelRoutine         STATE MACHINE 2 — the fuel cycle
│   ├── ShopPath            recorded click path, classified + persisted
│   └── ShopRecorder        learns the path from the player's own clicks
├── hud/
│   └── AutoSellHud         compact status overlay + progress bar
├── mixin/
│   ├── ContainerScreenMixin  observes player clicks (for recording)
│   ├── KeyboardHandlerMixin  raw key taps (works inside GUIs)
│   └── KeyboardInputMixin    injects synthetic movement
└── profit/
    └── ProfitTracker       parses sell confirmations, totals, CSV
```

## State machine 1 — `SellRoutine`

Trigger: triple-press `1` (`KeyboardHandlerMixin` → `AutoSellClient.onKeyPressed`).

```
IDLE
 └─ PRE_CHECK ─────── offenders in storage?
      ├─ yes ──> DUMP_WALK → DUMP_OPEN → DUMP_AWAIT → DUMP_DEPOSIT
      │              → DUMP_CLOSE → RETURN_WALK ┐
      └─ no ─────────────────────────────────────┤
                                                 ▼
                    OPEN_CHEST → AWAIT_CONTAINER → LOOT
                        → CLOSE_CONTAINER → VERIFY
                              ├─ offenders  → dump trip (once)
                              ├─ empty      → finish
                              └─ ok         → SELL → AWAIT_SELL
                                                 ├─ chest empty → finish
                                                 └─ else → OPEN_CHEST
```

- **Whitelist policy**: only `sellList` items are pulled from the chest.
- **Offender** = anything in main storage (slots 9–35) not on `sellList`
  and not on `keepList`.
- **A dump deposits everything except `keepList`**, hotbar included.

## State machine 2 — `FuelRoutine`

Trigger: `/autosell fuel` (or `fuel test` for a dry run).

```
WALK_DUMP → OPEN_DUMP → AWAIT_DUMP → DRAIN → CLOSE_DUMP
   → SHOP_OPEN → SHOP_MAIN/CATEGORY/ITEM   (replays recorded NAVIGATE steps)
   → SHOP_STACKS   (locate the counter, compute target)
   → SHOP_ADJUST   (click +1/+16/+32 greedily, wait for the counter to move)
   → SHOP_CONFIRM  (parse price, check caps, click — or stop if dry run)
   → AWAIT_GOODS
   → WALK_FARM → ALIGN_FARM
   → CRAFT_PLACE ⇄ CRAFT_PICKUP → CRAFT_INSERT → CRAFT_TAKE
                     └── no room / no bones → DROP ──┐
                                                     ▼
                                               CYCLE_END
                                    ├─ limits hit → finish
                                    └─ else → WALK_DUMP
```

### Craft loop detail (the fragile part)

```
CRAFT_PLACE   decision + hygiene:
                cursor holds something?  → clear it, return
                grid occupied?           → return one slot, return
                no bones?                → DROP or CYCLE_END
                free slots < 3?          → DROP, resume after
                otherwise                → CRAFT_PICKUP

CRAFT_PICKUP  one click: pick the stack up.  Wait until carried is non-empty.
CRAFT_INSERT  one click: drop it in grid slot 1. Wait until grid occupied.
CRAFT_TAKE    result present? shift-click it (one per tick).
              result empty + grid full  → WAITING (server hasn't answered)
              result empty + grid empty → back to CRAFT_PLACE
```

One bone stack (64) → 192 bone meal (3 stacks). Net −2 slots per craft,
which is why 6 reserved slots converts 3 stacks before a drop is needed.

## Movement

`Walker` has three modes, in preference order:

1. **Baritone** (`BaritoneWalker`, pure reflection, optional dependency).
   Used whenever the `baritone` mod is loaded. Handles real terrain.
2. **Built-in A\*** (`PathFinder`) — short range, bounded 48 blocks / 6000
   nodes. Fallback when Baritone is absent.
3. **Fine alignment** (`Walker.startAlign`) — strafes onto an exact
   sub-block position while holding a fixed yaw. Always ours, even under
   Baritone, and runs after Baritone finishes.

`KeyboardInputMixin` injects at TAIL of `KeyboardInput.tick()` and yields
entirely to Baritone unless aligning.

## Shop path — recorded, not configured

`ShopRecorder` watches the player's own clicks via `ContainerScreenMixin`
and classifies each one by observing what changed:

| Observation after the click | Classified as |
|---|---|
| screen title changed | `NAVIGATE` |
| amount counter changed | `ADJUST`, delta = the change |
| nothing, then the GUI closed | `PURCHASE` (last non-adjust click) |

Persisted to `config/autosell-shoppath.json`. On replay, a step resolves by
its recorded slot **only if the item id and folded name still match**;
otherwise by name search; otherwise the run aborts. The purchase step uses
a stricter exact-match resolver.

Quantity buttons are additionally **auto-discovered** at runtime by parsing
labels matching `^[+-]\d+$`, so `+1/+16/+32/-1/-16` are found without
recording.

## Safety rails on spending

- price parsed from the confirm button's own name/lore
- aborts if the price is unreadable — never clicks blind
- aborts if price > `fuelMaxSpend` (default $30,000)
- stops if the purchase would drop balance below `fuelMoneyFloor`
- `/autosell fuel test` walks the entire path and stops before buying

## Diagnostics

| File | Contents |
|---|---|
| `config/autosell-debug.log` | every phase change + state dump; full dump on abort/stall |
| `config/autosell-guidump.txt` | every server GUI that opened, with lore |
| `config/autosell-log.csv` | profit history |

`DebugLog` rotates at 1 MB. `dumpState()` is the single most useful thing
in the codebase for remote debugging.
