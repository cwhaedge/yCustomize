# DyeBrush — Remaining Work Plan

> **STATUS (2026-06-10): both issues implemented.**
> A: `HypixelDyes.ANIMATION_SLOWDOWN = 2.0f` applied at evaluate time (no cache
> bump needed for tuning) — calibrate in game if still off.
> B: NEU `constants/animatedskulls.json` (741 animated skins, incl.
> PANDA_SPIRIT_DAY/NIGHT, WITHER_GOGGLES_CELESTIAL) is parsed into a third cache
> (`dyebrush_animskindb.json`); `SkinEntry` holds frame textures + tick rate;
> the per-tick applier cycles frames; picker shows animated entries with ✨.
> Cache version bumped to 5 (forces one refetch). Awaiting in-game verification.

Two open issues reported in game (everything else confirmed working: per-item dyes,
wardrobe persistence, live preview, helmet skins incl. unowned items, full 65-dye
list with real colors/animations from NEU `constants/dyes.json`).

## Issue A — Dye animations cycle too fast

**Symptom:** animated dyes (Black Ice etc.) visibly cycle faster than on Hypixel.

**Cause hypothesis:** `SkinDatabase.fetchFromNeuRepo()` assumes 1 animation frame
per game tick: `period = stops.length / 20f`. NEU's frame dumps are likely sampled
slower (every 2–4 ticks per frame). Black Ice = 130 frames → we play it in 6.5s;
if the real cadence is e.g. 3 ticks/frame it should be ~19.5s.

**Fix steps:**
1. In `SkinDatabase.java`, add a constant `ANIMATION_TICKS_PER_FRAME` and compute
   `period = stops.length * ANIMATION_TICKS_PER_FRAME / 20f`.
2. Calibrate in game: stand next to the real dye (or a YouTube clip of one) and
   compare loop time. Start with 2, try 3–4. User feedback decides.
3. Bump `CACHE_VERSION` ("4" → "5") in `SkinDatabase.java` so cached dye periods
   (stored in `config/dyebrush_dyedb.json`) get recomputed.
   Alternative (better, avoids future bumps): stop storing `period` in the cache;
   store frames only and compute the period in `HypixelDyes.registerAll`.

## Issue B — Animated helmet skins show only one frame

**Symptom:** applying a skin that animates on Hypixel (texture-cycling skull,
e.g. some Wither Goggle / event skins) renders a single static texture.

**Cause:** `SkinEntry` stores ONE `ProfileComponent`; Hypixel animates these
server-side by swapping the skull texture every few ticks. Copying once = 1 frame.

**Fix steps:**
1. **Research the data source first** (cheapest win): check whether NEU repo has
   animated-skin frame data, analogous to `constants/dyes.json`:
   - look in `constants/` for anything skin/texture related,
   - check whether animated-skin item JSONs carry multiple `Value:"..."` entries
     in their `nbttag` textures array (current regex only takes the first match —
     `fetchFromNeuRepo()` uses `m.find()` once),
   - look at how Firmament / SkyHanni / SkyCofl render animated skins (all open
     source) to find their data source.
2. **Extend `SkinEntry`**: replace single `profile` JSON field with a list
   `profileFrames` + `frameTicks` (keep reading the old single-field format for
   config back-compat — or just migrate, the store is per-user). Add
   `component(double timeSeconds)` returning the current frame's
   `ProfileComponent` (decode lazily, cache all frames after first use).
3. **Animate in the tick applier**: `DyeBrushClient.applySkinOverrides()` already
   runs every tick — compute current frame index from time, `stack.set(PROFILE,
   frame)` when the reference differs (same no-op guard as now). Skull textures
   are cached per-profile by the client, so after one full loop all frames render
   from cache; expect a brief pop-in on the first loop while each texture resolves.
4. **UI**: no changes needed if frames come from the DB (search result just carries
   frames). If NO data source exists, fallback feature: a "Record from worn item"
   button in SKIN mode that samples the worn helmet's PROFILE component over a few
   seconds while Hypixel animates it (only works for items the player owns and
   wears), saving the captured frames as the SkinEntry.
5. Bump skin DB `CACHE_VERSION` if the SKINS map format changes (it becomes
   name → frames list instead of name → single texture).

## Context for whoever picks this up

- Project: `C:\Users\charl\Documents\dyebrush\dyebrush`, build with `.\gradlew build`,
  jar lands in `build\libs\dyebrush-1.0.0.jar`. MC 1.21.11, Yarn `1.21.11+build.6`,
  Fabric API `0.141.4+1.21.11`, Gradle 9.1.0, JDK 21.
- User plays Hypixel SkyBlock via Prism Launcher; mods folder is the instance's
  `minecraft\mods`, NOT `%APPDATA%\.minecraft\mods`.
- Key files: `SkinDatabase.java` (downloads Hypixel items API + NEU repo zip, caches
  `dyebrush_skindb.json` / `dyebrush_dyedb.json`, version-gated, never caches partial
  fetches), `HypixelDyes.java` (dye registry, `DyeDef(stops[], periodSeconds)`),
  `SkinEntry.java` (profile-component copy + original for restore),
  `DyeBrushClient.applySkinOverrides` (per-tick re-assert), `DyePickerScreen` (UI:
  dropdowns are hand-rolled overlay, 1.21.11 uses `mouseClicked(Click, boolean)`),
  `DyedColorComponentMixin` (armor tint override — colors must be OPAQUE ARGB).
- Hypixel item UUIDs sit at the ROOT of the `custom_data` component on modern
  clients ("uuid" key), not under `ExtraAttributes`.
