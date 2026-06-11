# DyeBrush

An ultra-lightweight, client-side armor dyeing mod for Hypixel SkyBlock on
**Minecraft 1.21.10 (Fabric)**. It does exactly one thing: let you recolor your
armor with a hex color, a named Hypixel dye, or an animated/cycling dye — and
nothing else. No HUDs, no solvers, no Kotlin, no telemetry.

Total footprint: ~7 small source files. Depends only on Fabric Loader + Fabric API.

## What it does

- Adds a small 🖌 button to your inventory screen (and an optional keybind).
- Opens a tiny picker: pick an armor slot, pick a mode (Hex / Named / Animated), apply.
- Colors are **client-side only** — nobody else sees them.
- Assignments are keyed to the item's Hypixel UUID, so they follow the *item*,
  persist across relogs, and survive moving the piece around your inventory.
- Saved to `config/dyebrush.json`.

## Build it

You need JDK 21 and internet access to Mojang/Fabric maven (the sandbox this was
generated in can't reach those, so the jar is not pre-built).

```bash
# from the project root
./gradlew build        # Linux/macOS
gradlew.bat build      # Windows
```

The finished mod jar lands in `build/libs/dyebrush-1.0.0.jar`. Drop it in your
`mods/` folder alongside Fabric API.

If you don't have the Gradle wrapper jar, run `gradle wrapper --gradle-version 8.10`
once (requires a system Gradle), or open the folder in IntelliJ IDEA with the
Minecraft Development plugin and let it import.

## The two parts most likely to need tweaking

1. **`HypixelDyes.java`** — Hypixel doesn't publish official hex values for custom
   dyes, so the table holds community approximations. If a named dye looks off,
   edit one number and rebuild. That's the whole reason it's a flat, explicit table.

2. **`DyedColorComponentMixin.java`** — the render hook fires on Minecraft's
   *dyeable* color path. That covers leather-base armor (most recolorable Hypixel
   pieces). Truly non-dyeable armor models won't hit this path; recoloring those
   would need a second hook on the armor feature renderer, which was intentionally
   left out to keep the mod minimal. If you find a piece that won't take a color,
   that's why.

## Why not hook the actual vanilla brush?

The brush button opens the vanilla equipment-customization view, but its class
name shifts between versions and Hypixel sometimes routes customization through a
server-side GUI. Anchoring our entry point to the stable `InventoryScreen` (same
place the brush lives) is far less brittle and gives identical one-click access.
If you specifically want to replace the brush screen itself, that mixin target is
the one thing you'd swap in — everything else stays the same.

## License

MIT. Do whatever you want with it.
