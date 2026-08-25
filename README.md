# Minecraft AutoSell

Client-side Fabric mod for **Minecraft 26.2**, built for `planetpvp.ddns.net`.

Two automations, both triggered by the player:

- **Sell run** — triple-press `1`: clear the inventory to the dump chest if
  needed, loot bamboo from the loot chest, `/sellall inventory`, log the
  profit, repeat until the chest is empty.
- **Fuel cycle** — `/autosell fuel`: drain inventory, buy bones from the
  shop, walk to the farm spot, craft bone meal, drop it for the collector,
  repeat until a cycle or money limit.

Movement uses [Baritone](https://github.com/cabaletta/baritone) when
installed. Spending has hard caps and a dry-run mode.

---

## Continuing this project in a new conversation

**Read [`docs/HANDOFF.md`](docs/HANDOFF.md) first.** Then:

| Doc | What it covers |
|---|---|
| [HANDOFF.md](docs/HANDOFF.md) | current state, environment, the 7 rules that must not be broken, working style |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | full program map, both state machines, craft loop detail |
| [MC-26.2-NOTES.md](docs/MC-26.2-NOTES.md) | 26.2 API renames and verified signatures — **26.2 is past the training cutoff** |
| [BUG-HISTORY.md](docs/BUG-HISTORY.md) | 12 bugs with real root causes; most odd-looking code traces to one of these |
| [SETUP.md](docs/SETUP.md) | install (macOS + Windows), building, building Baritone |
| [COMMANDS.md](docs/COMMANDS.md) | every command and control |
| [DESIGN-NOTES.md](docs/DESIGN-NOTES.md) | narrative notes written during development |

`config-reference/` holds real captured data:
- `autosell.json`, `autosell-shoppath.json` — the working configuration
- `shop-gui-capture.txt` — real GUI dumps of the shop's bone path
- `debug-log-example.txt` — annotated log excerpts, healthy and broken

## Quick facts

| | |
|---|---|
| Minecraft | 26.2 (ships **deobfuscated** — no mappings layer) |
| Fabric Loader / API | 0.19.3 / 0.158.0+26.2 |
| Java | 25 |
| Build | `./gradlew build` → `build/libs/autosell-1.0.0.jar` |
| Source | 27 classes, ~6,400 lines |

## Status

Working and verified in play — a run reached cycle 21 with bone meal
climbing steadily and no unforced aborts. A full unattended run to the
cycle limit has not been observed yet.

## Licence

The mod is the author's own work. Baritone (LGPL-3.0) is an **optional
runtime dependency reached only by reflection** — never linked at compile
time — so its terms do not extend to this code. See `NOTICE.txt` in the
distribution.
