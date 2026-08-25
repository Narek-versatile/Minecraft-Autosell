# Bug history

Every non-obvious bug hit during development, with the real root cause.
Most of the odd-looking code exists because of something here. **Read
before "simplifying" anything.**

Ordered roughly as encountered.

---

## 1. Bought obsidian instead of bone

**Symptom** — the dry run navigated the shop and tried to buy obsidian.

**Cause** — shop slot indices had been read by counting grid squares in a
screenshot. At least one was off, so it clicked the wrong category and then
index 3 of *that* category. A slot index carries no information about what
it holds, so nothing could detect the mistake.

**Fix** — never address shop entries positionally. Record the path by
clicking it (`ShopRecorder`), store item id + name, and resolve by identity.
A miss aborts and prints the screen's actual contents.

**Lesson** — reading a UI from a screenshot is inference, not data.

---

## 2. `MOBS` never matched

**Symptom** — name matching failed even after switching away from indices.

**Cause** — the server writes in Unicode small capitals. The category is
`ᴍᴏʙs` — `U+1D0D U+1D0F U+0299` + ASCII `s`. `normalize()` stripped
everything outside `[A-Z0-9 ]`, reducing it to an **empty string**.

**Fix** — `TextFold.normalize`: map small-cap codepoints to ASCII, NFKD
decompose, strip combining marks, then uppercase.

**Also** — the user cannot type `ᴍᴏʙꜱ` into a config file, which is an
independent reason the path must be recorded rather than configured.

---

## 3. Chests wouldn't open — "it looks into air"

**Symptom** — the character stood next to a chest, facing nowhere, not
opening it.

**Cause** — the code fabricated a `BlockHitResult` at the block centre and
fired `useItemOn` immediately, while facing elsewhere. The server validates
where the player is looking and silently discards the interaction.

**Fix** — `BlockOpener`: turn toward the block at a limited rate, wait until
the **live** `mc.hitResult` is on that block, then interact with the real
hit result.

---

## 4. Walker got stuck constantly

**Symptom** — wedged on terrain, jumping in place after falls, "rushing".

**Causes** — three at once: straight-line steering cannot pass a wall; the
view snapped to an exact angle each tick (jerky); "no progress" counted
falling as stuck, so it jumped mid-air.

**Fix** — A\* (`PathFinder`), rate-limited turning, speed scaled by heading
error and distance, jump cooldown, falling excluded from stuck detection.
Later superseded by Baritone, which the user explicitly asked for.

---

## 5. Bone meal wouldn't drop; a stack stuck to the cursor

**Symptom** — bone meal accumulated and would not drop until the user
manually cleared the cursor.

**Cause** — `ContainerInput.THROW` checks `getCarried().isEmpty()` and
**skips the entire drop path** otherwise (verified at bytecode offset
`1457`: `getCarried` → `isEmpty` → `ifeq`). A stack stranded on the cursor
silently disabled every drop.

**Fix** — clear the cursor before any craft or drop: merge onto a matching
stack, else an empty slot, else throw it outside (`SLOT_CLICKED_OUTSIDE`).

---

## 6. Ingredients wobbled in and out of the grid

**Symptom** — the bone stack visibly bounced between inventory, cursor and
crafting grid at ~5 Hz. Nothing ever crafted.

**Diagnosis** — the debug log settled it instantly:
```
bones=1216   64 leave the inventory
bones=1280   64 come straight back
meal=186     never changes
```

**Two causes, found in order:**

**(a)** `CRAFT_TAKE` ran one tick after placing, saw an empty result slot,
concluded "nothing craftable" and pulled the ingredients back out. But
crafting is computed **server-side only** —
`InventoryMenu.slotsChanged` → `CraftingMenu.slotChangedCraftingGrid(...,
ServerLevel, ...)`. The client result slot is empty until a round trip
completes.

**(b)** The deeper cause: `CRAFT_PLACE` fired **two clicks in one tick**
(pick up, then insert). `ServerboundContainerClickPacket` carries a
`stateId`; both clicks were stamped with the same one, so the server
accepted the first, advanced its state, then rejected the second as stale
and **resynced the container**, undoing the insert.

**Fix** — split into `CRAFT_PICKUP` and `CRAFT_INSERT`, one click each, and
have every step wait for its own observable effect rather than a delay.
Empty result + occupied grid now means *waiting*.

---

## 7. Character vibrating in place; no server-side movement

**Symptom** — the user's friend reported the character not moving at all
server-side, while it visibly twitched on the user's client.

**Cause** — sign inversion. Vanilla builds
`new Vec2(calculateImpulse(left, right), calculateImpulse(forward, backward))`,
so **`moveVector.x` is the LEFT impulse**. Fine alignment computed the
offset's component along **right** and fed it in unchanged, so every
sideways correction pushed the wrong way — turning the controller from
negative into **positive feedback**. It oscillated about the target;
wedged between blocks that became twitching with ~zero net displacement.

**Fix** — negate it (`double left = -right;`), plus per-axis deadzone,
capped proportional speed, and "good enough" acceptance after 30 ticks
without progress so an unreachable exact position cannot be ground against.

---

## 8. Worked on the first run, wedged on the second

**Symptom** — flawless first run, stuck on the next.

**Cause** — `start()` reset 4 fields; **11 others carried over**. Stale
`qtySlot`, `awaitingCount`, `resumeCraftAfterDrop`, watchdog counters, and
a `BlockOpener` whose retry budget was already spent (callers skipped
`start()` when the target matched, so it reported failure immediately).

**Fix** — a single `resetState()` covering every run-scoped field, called
from `start()`, `abort()` and `finish()`; `BlockOpener.stop()` clears its
counters; callers use `ensureTargeting()`.

---

## 9. Bought a fixed 18 stacks regardless of free space

**Symptom** — the buy amount ignored the free-space calculation.

**Cause** — the recorder treated *every* click before the last as
navigation to replay verbatim, so the user's `+16` during recording was
baked in as a fixed step.

**Fix** — classify clicks by observing their effect: title change =
navigate, counter change = adjust (storing the measured delta), last
non-adjust = purchase. Plus runtime auto-discovery of `+N`/`-N` buttons by
label, so they need not be recorded at all.

---

## 10. Clicked an amount button instead of purchasing

**Symptom** — a run added another stack rather than buying.

**Causes** — the confirm step used the same lenient name search as
navigation, which can fall through to a fuzzy match; and the adjust loop
re-read the counter after a fixed 3-tick delay, so under lag it clicked
again on a stale reading and overshot.

**Fix** — a strict resolver for the purchase button (exact item id **and**
exact folded name, else abort), and the adjust loop waits for the counter
to actually change before the next click.

---

## 11. Couldn't reach a farm spot recorded between blocks

**Symptom** — `ABORT in WALK_FARM - Stuck on the way to the farm spot.`

**Cause** — the spot was recorded while straddling two blocks, so
`blockPosition()` resolved to a block that is not standable, which Baritone
correctly reports as unreachable.

**Fix** — path to `PathFinder.nearestStandable(...)` instead, and let the
strafe-alignment step cover the exact sub-block placement. If pathing gives
up within 6 blocks, alignment walks the remainder.

---

## 12. Dropped items missed the collector

**Symptom** — right angle, wrong landing spot.

**Cause** — `/autosell fuel spot` saved only `blockPosition()` (integers).
A throw arc starts from the player's exact sub-block position.

**Fix** — record exact doubles plus yaw/pitch; strafe onto the precise
point while holding the recorded facing; re-align if drift exceeds 0.8.
