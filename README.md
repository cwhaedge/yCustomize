# yCustomize

A lightweight, client-side armor customizer for Hypixel SkyBlock on
**Minecraft 1.21.11 (Fabric)**. Recolor your armor with hex colors, real Hypixel
dyes (including animated ones), or custom animated gradients — and reskin your
helmet with any SkyBlock helmet skin, whether you own it or not. Everything is
visual and client-side only; nothing is sent to the server.

## Features

- **Per-item dyes** — hex color, any of the ~65 named Hypixel dyes (exact colors
  and real animation sequences), or a custom animated gradient.
- **Helmet skins** — search every SkyBlock skull item and cosmetic skin (sourced
  from the Hypixel items API + the NEU community repo) and apply its look to the
  helmet you're wearing. Animated skins (Panda Spirit, Celestial variants, …)
  play their real frame sequences.
- **Live preview** — your character renders in the picker and updates as you type
  a hex code or browse dyes, before you hit Apply.
- **Wardrobe-safe** — assignments are keyed to the item's Hypixel UUID, so they
  follow the *piece* through the wardrobe, relogs, and inventory shuffles.

## Usage

Open your inventory and click the 🖌 button on the character preview box, or bind
a key under Controls → yCustomize. Pick a piece, pick a mode (Hex / Named Dye /
Animated / Helmet Skin), apply. Clear removes everything from the selected piece.

On first launch the mod downloads its item/dye/skin databases (~25 MB, one time)
and caches them in `config/ycustomize_*.json`. Your assignments live in
`config/ycustomize.json`.

## Install

Requires [Fabric Loader](https://fabricmc.net/) 0.17+ and
[Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11, Java 21.
Grab the jar from [Releases](https://github.com/cwhaedge/yCustomize/releases) and
drop it in your `mods/` folder.

## Build

```bash
./gradlew build        # Linux/macOS
gradlew.bat build      # Windows
```

The jar lands in `build/libs/`.

## Notes

- Dyes only recolor pieces vanilla treats as dyeable (leather-base armor — which
  covers most recolorable SkyBlock pieces). Skull-based helmets take *skins*
  instead; that's what Helmet Skin mode is for.
- Dye colors/animations are read from NEU's `constants/dyes.json` at runtime, so
  new dyes appear automatically. Animation speed is scaled by
  `HypixelDyes.ANIMATION_SLOWDOWN` if it ever drifts from the real thing.

## License

MIT. Do whatever you want with it.
