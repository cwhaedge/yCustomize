package com.example.dyebrush.mixin;

import com.example.dyebrush.client.DyeBrushClient;
import com.example.dyebrush.client.DyePickerScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a small "Dye" button to the survival inventory screen.
 *
 * This is the practical equivalent of "hooking the brush": the vanilla brush
 * button opens the equipment-customization view, but its exact class is volatile
 * across versions and Hypixel sometimes routes customization through a server
 * GUI. Anchoring to the stable InventoryScreen and adding our own entry point is
 * far less brittle and gives the same one-click access right where the brush is.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends Screen {

    protected InventoryScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void dyebrush$addButton(CallbackInfo ci) {
        int x = (this.width - 176) / 2;       // vanilla GUI width is 176
        int y = (this.height - 166) / 2;      // vanilla GUI height is 166
        // Top-right corner of the character-preview window (box spans 26,8 → 75,78).
        this.addDrawableChild(ButtonWidget.builder(Text.literal("🖌"), btn -> openPicker())
                .dimensions(x + 61, y + 9, 14, 14)
                .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("DyeBrush")))
                .build());
    }

    private void openPicker() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity p = mc.player;
        if (p == null) return;
        mc.setScreen(new DyePickerScreen(this, DyeBrushClient.currentArmor(p)));
    }
}
