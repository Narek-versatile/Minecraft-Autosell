# Handoff — read this first

Continuation context for picking this project up in a fresh conversation.
Read this file, then `ARCHITECTURE.md`, then `MC-26.2-NOTES.md`.
`BUG-HISTORY.md` is worth skimming before changing anything — most of the
non-obvious code exists because of a specific bug listed there.

## What this is

A client-side Fabric mod for **Minecraft 26.2**, written for one player
(Narek, `NarekTM`) on one server (`planetpvp.ddns.net`). It does two things:

1. **Sell run** — triple-press `1`: walk to the dump chest if the inventory
   is dirty, empty it, walk back to the loot chest, bulk-transfer bamboo,
   run `/sellall inventory`, parse the profit from chat, repeat until the
   chest is empty.
2. **Fuel cycle** — `/autosell fuel`: drain the inventory into the dump
   chest, `/shop` → MOBS → BONE → buy stacks → purchase, walk to the farm
   spot, craft bones into bone meal in the 2×2 grid, drop it on the ground
   for the farm's collector, repeat until the cycle or money limit.

## Current state

**Working, and verified in live play.** The debug log from 2026-08-25 shows
a fuel run reaching **cycle 21** with bone meal climbing to **2112**, a clean
`CRAFT_PLACE → CRAFT_PICKUP → CRAFT_INSERT → CRAFT_TAKE` loop, and exactly
one abort in the entire file — `Cancelled.`, i.e. the user pressing stop.

That confirms the last three fixes in real play:
- split `CRAFT_PICKUP` / `CRAFT_INSERT` states (the crafting wobble)
- `resetState()` in both routines (worked once, wedged after)
- strafe sign in `Walker.tickAlign` (vibrating in place)

See `config-reference/debug-log-example.txt` for an annotated healthy
excerpt alongside the historical failure signatures.

Still not confirmed: a full **unattended** run to the cycle limit. Every
observed run so far ended with the user stopping it.

If anything regresses, `config/autosell-debug.log` names the phase and dumps
state on every transition — that log resolved several bugs on first read
where description alone went in circles. **Always ask for it before
theorising.**

## Environment

| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.158.0+26.2 |
| Java | 25 |
| Loom | 1.17-SNAPSHOT (resolves to 1.17.19) |
| Launcher | ElyPrismLauncher, instance `26.2` |
| Mods dir (Mac) | `~/Library/Application Support/ElyPrismLauncher/instances/26.2/minecraft/mods/` |

Build:
```
export JAVA_HOME=~/Library/Application\ Support/ElyPrismLauncher/java/java-runtime-epsilon
./gradlew build
```
Output: `build/libs/autosell-1.0.0.jar`.

**There is no `mappings` dependency in `build.gradle`, and that is correct.**
See `MC-26.2-NOTES.md` before touching the build file.

## Hard-won rules — violate these and it breaks

1. **One container click per tick.** Clicks carry a `stateId`; two in one
   tick share it, the server rejects the second and resyncs. This is why
   placing an ingredient is two states, not two calls.
2. **Crafting results are server-side only.** An empty result slot while
   the grid holds ingredients means *waiting*, not *finished*.
3. **`THROW` is a no-op while the cursor holds anything.** Clear the cursor
   before any drop.
4. **`moveVector.x` is the LEFT impulse**, not right. Getting this backwards
   turns fine positioning into positive feedback.
5. **Never guess server GUI slot indices.** Match by recorded item id +
   folded name. Reading slots off a screenshot once bought obsidian.
6. **The server writes in Unicode small-caps** (`ᴍᴏʙꜱ`, not `MOBS`).
   Everything user-facing goes through `TextFold.normalize`.
7. **Reset all run-scoped state** in `start()`/`abort()`/`finish()`.

## Working style that worked

- The user tests in-game and reports symptoms; you cannot test.
- `javap` against the real client jar beats recalling 1.21.x APIs — 26.2
  renamed a lot and is past the training cutoff.
- When a symptom is vague, ask for `autosell-debug.log` rather than guess.
- The user asked for every reply to end with **"what I need from you"** and
  a **step-by-step testing manual**. Keep doing that.

## Open / possible next steps

- Never run unattended for a full 5-cycle fuel run.
- `keepList` is empty in the shipped config — a dump would deposit the
  user's pickaxes and sword. They have been told to run `/autosell keep add`.
- Chest transfer loops still burst multiple clicks per tick. They work
  because the loop re-scans and retries, but they violate rule 1 and may be
  silently retrying. Worth converting if looting ever looks slow.
- The user mentioned wanting to "pin" several chests and automate the whole
  circuit later. Not started.
