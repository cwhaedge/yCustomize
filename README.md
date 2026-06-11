# yCustomize

**Dye any armor piece and reskin any helmet in Hypixel SkyBlock — client-side, for free.**

[![Release](https://img.shields.io/github/v/release/cwhaedge/yCustomize?label=download&color=brightgreen)](https://github.com/cwhaedge/yCustomize/releases/latest)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-blue)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)

yCustomize is a lightweight Fabric mod that lets you preview and wear any armor
color or helmet skin in SkyBlock without owning the dye or the skin. Everything
is rendered on your client only — other players see your normal gear, nothing is
ever sent to the server, and no gameplay is affected.

## Features

🎨 **Every Hypixel dye, for free** — all ~65 named dyes with their exact colors,
including animated ones (Black Ice, Aurora, Rose, …) playing their real
frame-by-frame animations.

🌈 **Any color you want** — type a hex code for a flat color, or use a custom
animated gradient.

🎭 **Any helmet skin on any helmet** — search the full catalog of SkyBlock skull
items and cosmetic skins and apply one to whatever you're wearing. Animated
skins (Panda Spirit, Celestial variants, …) animate for real.

👀 **Live preview** — your character renders inside the picker and updates as
you type or browse, before you commit.

🧳 **Wardrobe-proof** — dyes and skins are tied to each item's unique Hypixel ID,
not its slot. Swap wardrobes, relog, reorganize — your look stays on the piece
it belongs to.

🔄 **Self-updating data** — dye colors, animations, and the skin catalog are
downloaded from the Hypixel API and the NEU community repo on first launch
(one-time, ~25 MB) and cached. New dyes and skins appear without a mod update.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) 0.17+ for Minecraft 1.21.11 (Java 21).
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and
   [`yCustomize-1.21.11.jar`](https://github.com/cwhaedge/yCustomize/releases/latest)
   into your `mods` folder.

## Usage

1. Open your inventory and click the **🖌 button** on the character preview
   (or bind a key: Options → Controls → yCustomize).
2. Pick the piece — shown by its actual item name, e.g. `Helmet: Spirit Mask`.
3. Pick a mode:
   | Mode | What it does |
   |---|---|
   | **Hex** | Flat color from a `#RRGGBB` code |
   | **Named Dye** | Dropdown of every Hypixel dye, color swatch included, ✨ = animated |
   | **Animated** | Custom looping gradient |
   | **Helmet Skin** | Search box over every SkyBlock helmet skin, ✦ = in your inventory, ✨ = animated |
4. **Apply.** **Clear** removes everything from the piece; **Done** closes.

Your assignments are saved in `config/ycustomize.json` and restored every session.

## Good to know

- **Dyes need dyeable armor.** Vanilla only tints leather-base armor — which is
  exactly what Hypixel uses for recolorable pieces, so nearly everything that
  *can* hold a dye on Hypixel works here too. Skull-based helmets (Spirit Mask
  and friends) take **skins** instead — that's what Helmet Skin mode is for.
- **First launch downloads the databases.** If the skin search says
  "Downloading…", give it a minute; it's cached afterwards.
- **Animated skins flicker on their first loop** while each frame's texture
  loads, then play smoothly forever.
- **Only you see it.** This is a cosmetic preview on your own screen — it
  doesn't modify items, send packets, or give any advantage.

## Building from source

```bash
git clone https://github.com/cwhaedge/yCustomize.git
cd yCustomize
./gradlew build      # gradlew.bat build on Windows
```

The jar lands in `build/libs/`. Requires JDK 21; Gradle comes via the wrapper.

## Credits

- [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO)
  — community-maintained item data: dye colors/animations and animated skin frames.
- [Hypixel API](https://api.hypixel.net/) — base item catalog.

## License

[MIT](LICENSE) — do whatever you want with it.
