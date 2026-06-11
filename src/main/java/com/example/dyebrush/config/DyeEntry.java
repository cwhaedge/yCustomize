package com.example.dyebrush.config;

/**
 * A single saved dye assignment for one armor piece (keyed by its Hypixel item UUID).
 *
 * Three modes:
 *   STATIC   - one fixed RGB color (hex).
 *   NAMED    - a Hypixel "custom dye" name; resolved to a hex via {@link HypixelDyes}.
 *   ANIMATED - cycles smoothly through a list of color stops at a given speed.
 *
 * All colors are stored as 0xRRGGBB ints (no alpha). Rendering is client-side only.
 */
public class DyeEntry {

    public enum Mode { STATIC, NAMED, ANIMATED }

    public Mode mode = Mode.STATIC;

    /** STATIC: the color. NAMED: cached resolved color. */
    public int color = 0xFFFFFF;

    /** NAMED: the Hypixel dye name (e.g. "Sunset Dye"). */
    public String dyeName = "";

    /** ANIMATED: ordered list of 0xRRGGBB stops to cycle through. */
    public int[] animatedStops = new int[0];

    /** ANIMATED: seconds for one full loop through all stops. */
    public float animatedPeriodSeconds = 4.0f;

    public static DyeEntry ofStatic(int rgb) {
        DyeEntry e = new DyeEntry();
        e.mode = Mode.STATIC;
        e.color = rgb & 0xFFFFFF;
        return e;
    }

    public static DyeEntry ofNamed(String name) {
        DyeEntry e = new DyeEntry();
        e.mode = Mode.NAMED;
        e.dyeName = name;
        e.color = HypixelDyes.resolve(name);
        return e;
    }

    public static DyeEntry ofAnimated(int[] stops, float periodSeconds) {
        DyeEntry e = new DyeEntry();
        e.mode = Mode.ANIMATED;
        e.animatedStops = stops != null ? stops : new int[0];
        e.animatedPeriodSeconds = periodSeconds <= 0 ? 4.0f : periodSeconds;
        return e;
    }

    /**
     * Resolve this entry to a concrete 0xRRGGBB color for the current frame.
     *
     * @param timeSeconds a monotonically increasing time source (world time / 1000f, etc.)
     */
    public int evaluate(double timeSeconds) {
        switch (mode) {
            case STATIC:
                return color & 0xFFFFFF;
            case NAMED:
                // Evaluated live so animated Hypixel dyes (Black Ice, ...) actually
                // animate, and table tweaks retint saved entries. Falls back to the
                // color cached at assign time if the name is no longer known.
                if (dyeName != null && HypixelDyes.isKnown(dyeName)) {
                    return HypixelDyes.evaluate(dyeName, timeSeconds);
                }
                return color & 0xFFFFFF;
            case ANIMATED:
                return cycleColors(animatedStops, animatedPeriodSeconds, timeSeconds);
            default:
                return 0xFFFFFF;
        }
    }

    /** Loop smoothly through {@code stops} once per {@code periodSeconds}. */
    public static int cycleColors(int[] stops, float periodSeconds, double timeSeconds) {
        if (stops == null || stops.length == 0) return 0xFFFFFF;
        if (stops.length == 1) return stops[0] & 0xFFFFFF;

        // Position around the loop in [0, stops.length)
        double period = Math.max(0.05, periodSeconds);
        double phase = (timeSeconds % period) / period;          // [0,1)
        double scaled = phase * stops.length;                    // [0, n)
        int i = (int) Math.floor(scaled);
        double t = scaled - i;                                   // [0,1) within segment
        int a = stops[i % stops.length] & 0xFFFFFF;
        int b = stops[(i + 1) % stops.length] & 0xFFFFFF;
        return lerpColor(a, b, (float) t);
    }

    /** Linear interpolation in sRGB space — cheap and good enough for armor tint. */
    private static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
