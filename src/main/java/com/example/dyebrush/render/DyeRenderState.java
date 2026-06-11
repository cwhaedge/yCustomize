package com.example.dyebrush.render;

import com.example.dyebrush.config.DyeEntry;
import com.example.dyebrush.config.DyeStore;
import net.minecraft.item.ItemStack;

/**
 * Bridges the render mixin and the dye store.
 *
 * The mixin hands us the ItemStack currently being rendered as armor; we return
 * an override color (or a sentinel meaning "leave vanilla color alone").
 *
 * Time source for animated dyes is the client render time in seconds; we read it
 * lazily so this class has no hard dependency on render internals.
 */
public final class DyeRenderState {

    private DyeRenderState() {}

    /** Sentinel: no override; the mixin should not change anything. */
    public static final int NO_OVERRIDE = Integer.MIN_VALUE;

    // Transient, unsaved dye shown while the picker is open ("live preview").
    // Wins over the persisted entry for the one item being edited.
    private static volatile String previewUuid;
    private static volatile DyeEntry previewEntry;

    public static void setPreview(String uuid, DyeEntry entry) {
        previewUuid = uuid;
        previewEntry = entry;
    }

    public static void clearPreview() {
        previewUuid = null;
        previewEntry = null;
    }

    /**
     * Resolve the override color for a worn armor stack, or NO_OVERRIDE.
     *
     * @param stack       the armor ItemStack being rendered
     * @param timeSeconds monotonically increasing seconds (for animated dyes)
     */
    public static int colorFor(ItemStack stack, double timeSeconds) {
        String uuid = DyeStore.hypixelUuid(stack);
        if (uuid == null) return NO_OVERRIDE;

        DyeEntry entry;
        String pu = previewUuid;
        if (pu != null && pu.equals(uuid) && previewEntry != null) {
            entry = previewEntry;
        } else {
            entry = DyeStore.get().forUuid(uuid);
        }
        if (entry == null) return NO_OVERRIDE;
        return entry.evaluate(timeSeconds) & 0xFFFFFF;
    }

    /** Packs an 0xRRGGBB into the 0xFFRRGGBB (opaque) form the renderer expects. */
    public static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }
}
