package com.example.dyebrush.mixin;

import com.example.dyebrush.render.DyeRenderState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides the dyed-armor color lookup so our saved dyes win — client-side only.
 *
 * {@code DyedColorComponent.getColor(ItemStack, int)} is the static helper the
 * armor render layer uses to decide a piece's tint. We inject at its return and,
 * if our store has an entry for this exact Hypixel item, substitute our color.
 *
 * IMPORTANT LIMITATION: this path only fires for items the renderer treats as
 * dyeable (those that carry / default a DYED_COLOR). Most leather-base Hypixel
 * armor qualifies. For armor that is NOT dyeable by vanilla rules, this hook
 * won't be invoked and a second hook on the armor feature renderer would be
 * needed — left out here to keep the mod tiny. In practice the dyeable path
 * covers the armor people actually want to recolor.
 */
@Mixin(DyedColorComponent.class)
public class DyedColorComponentMixin {

    @Inject(method = "getColor(Lnet/minecraft/item/ItemStack;I)I", at = @At("RETURN"), cancellable = true)
    private static void dyebrush$override(ItemStack stack, int defaultColor, CallbackInfoReturnable<Integer> cir) {
        double time = renderTimeSeconds();
        int override = DyeRenderState.colorFor(stack, time);
        if (override != DyeRenderState.NO_OVERRIDE) {
            // Must be opaque ARGB — alpha 0 renders the armor invisible in 1.21+.
            cir.setReturnValue(DyeRenderState.opaque(override));
        }
    }

    private static double renderTimeSeconds() {
        // Wall clock, not world ticks: ticks only advance 20×/sec which makes
        // animated dyes visibly steppy. nanoTime is monotonic and per-frame smooth.
        return System.nanoTime() / 1_000_000_000.0;
    }
}
