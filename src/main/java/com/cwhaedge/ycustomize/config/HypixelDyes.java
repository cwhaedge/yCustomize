package com.cwhaedge.ycustomize.config;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hypixel "custom dye" name -> color ramp.
 *
 * Static dyes have one stop; animated dyes (Black Ice, Aurora, ...) cycle through
 * several. On Hypixel the animation is server-driven with no published values, so
 * ramps here are community-sourced approximations — tweak freely, one line each.
 * Named dyes are evaluated at render time, so edits here retint already-saved
 * assignments on next launch.
 */
public final class HypixelDyes {

    private HypixelDyes() {}

    /**
     * Global speed scale for animated dyes. Base periods assume NEU's frame dumps
     * are 1 frame per game tick, which runs visibly faster than Hypixel's real
     * animations — this slows them down uniformly. Tune against the real thing.
     */
    public static final float ANIMATION_SLOWDOWN = 2.0f;

    /** A dye definition: one stop = static, multiple = looping gradient. */
    public record DyeDef(int[] stops, float periodSeconds) {
        public boolean animated() { return stops.length > 1; }
        public int swatch() { return stops[0]; }
        public int evaluate(double timeSeconds) {
            return DyeEntry.cycleColors(stops, periodSeconds * ANIMATION_SLOWDOWN, timeSeconds);
        }
    }

    private static final Map<String, DyeDef> BY_DISPLAY = new ConcurrentHashMap<>();
    private static final Map<String, DyeDef> BY_NORM = new ConcurrentHashMap<>();

    static {
        // Offline fallback only — the full, authoritative list (with Hypixel's real
        // hex values parsed from dye item lore) is loaded by SkinDatabase from the
        // NEU repo via registerAll() and OVERRIDES anything here.
        add("Sunset Dye",     4f, 0xF0633C);
        add("Sunflower Dye",  4f, 0xF6C544);
        add("Necron Dye",     4f, 0xE7413C);
        add("Aquamarine Dye", 4f, 0x7FFFD4);
        add("Celeste Dye",    4f, 0xB2FFFF);
        add("Black Ice Dye",  4f, 0x0B2031, 0x091B2C);
    }

    /**
     * Merge runtime-loaded dyes (NEU repo / cache). Real data wins over the
     * fallback table above. Called from the loader's background thread.
     */
    public static void registerAll(Map<String, DyeDef> defs) {
        for (Map.Entry<String, DyeDef> e : defs.entrySet()) {
            BY_DISPLAY.put(e.getKey(), e.getValue());
            BY_NORM.put(normalize(e.getKey()), e.getValue());
        }
    }

    private static void add(String displayName, float period, int... stops) {
        int[] clean = new int[stops.length];
        for (int i = 0; i < stops.length; i++) clean[i] = stops[i] & 0xFFFFFF;
        DyeDef def = new DyeDef(clean, period);
        BY_DISPLAY.put(displayName, def);
        BY_NORM.put(normalize(displayName), def);
    }

    private static String normalize(String name) {
        String s = name.trim().toLowerCase();
        if (s.endsWith(" dye")) s = s.substring(0, s.length() - 4).trim();
        return s;
    }

    /** Resolve the CURRENT color of a named dye (handles animation). */
    public static int evaluate(String name, double timeSeconds) {
        DyeDef def = name == null ? null : BY_NORM.get(normalize(name));
        return def == null ? 0xFFFFFF : def.evaluate(timeSeconds);
    }

    /** Static/representative hex for a named dye (first stop), or white if unknown. */
    public static int resolve(String name) {
        DyeDef def = name == null ? null : BY_NORM.get(normalize(name));
        return def == null ? 0xFFFFFF : def.swatch();
    }

    public static boolean isKnown(String name) {
        return name != null && BY_NORM.containsKey(normalize(name));
    }

    /** Display names (as originally cased) for building the picker dropdown. */
    public static Set<String> names() {
        return BY_DISPLAY.keySet();
    }

    /** Swatch color for the dropdown. */
    public static int resolveDisplay(String displayName) {
        DyeDef def = BY_DISPLAY.get(displayName);
        return def == null ? 0xFFFFFF : def.swatch();
    }

    public static boolean isAnimatedDisplay(String displayName) {
        DyeDef def = BY_DISPLAY.get(displayName);
        return def != null && def.animated();
    }
}
