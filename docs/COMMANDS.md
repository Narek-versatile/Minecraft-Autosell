# Commands & controls

All client-side. None of these reach the server (the mod only sends
`/sellall inventory` and `/shop`, which are the server's own commands).

## Controls

| Input | Effect |
|---|---|
| triple-press `1` | run the sell cycle (key + window configurable) |
| `P` inside a container GUI | dump that GUI to `autosell-guidump.txt` |

The trigger only fires in-world or with a container open — never in chat,
a sign, or the settings screen, so `1` stays a normal hotbar key elsewhere.

## Setup

| Command | Effect |
|---|---|
| `/autosell add` / `remove` | add/remove the held item on the sell-list |
| `/autosell list` / `clear` | show or empty the sell-list |
| `/autosell keep add` / `remove` / `list` | gear that is never dumped or sold |
| `/autosell loot set` | loot chest = block you are looking at |
| `/autosell dump set` / `clear` | dump chest = block you are looking at |
| `/autosell fuel spot` | farm spot = where you stand **and face** |
| `/autosell fuel record` | record the shop click path |
| `/autosell fuel path` | show the recorded path |

## Running

| Command | Effect |
|---|---|
| `/autosell fuel test` | dry run — walks the whole path, never buys |
| `/autosell fuel` | run the fuel cycle for real |
| `/autosell fuel stop` | abort the fuel cycle |
| `/autosell stop` | abort the sell cycle |
| `/autosell check` | dry-run the inventory check |
| `/autosell fuel status` | config summary + live balance |

## Tuning

| Command | Default |
|---|---|
| `/autosell key <glfw>` | 49 (`1`) |
| `/autosell window <ms>` | 600 |
| `/autosell delay <ticks>` | 2 (container navigation) |
| `/autosell fuel stacks <n>` | 0 = auto (free slots − reserve) |
| `/autosell fuel reserve <slots>` | 6 |
| `/autosell fuel cycles <n>` | 25 |
| `/autosell fuel floor <amount>` | 0 = disabled |
| `/autosell fuel maxspend <amount>` | 30000 |
| `/autosell command <cmd>` | `sellall inventory` |
| `/autosell regex <pattern>` | see below |
| `/autosell loop` / `burst` / `walk` / `hud` | toggles |
| `/autosell guilog` / `debug` | toggle the log files |
| `/autosell inspectkey <glfw>` | 80 (`P`) |
| `/autosell stats` / `reset` | session profit |
| `/autosell` or `/autosell config` | settings screen |

## Sell confirmation pattern

Default, matching PlanetPVP's
`ᴘʟᴀɴᴇᴛ ᴘᴠᴘ ▶ You have successfully sold 2304 items for $23,040.00`:

```
sold\s+([\d,]+)\s+items?\s+for\s+\$([\d,]+(?:\.\d+)?)
```

Group 1 = item count, group 2 = money. Formatting is stripped before
matching, so colour codes and the small-caps prefix do not matter.
