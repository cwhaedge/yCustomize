package com.example.dyebrush.client;

import com.example.dyebrush.config.DyeStore;
import com.example.dyebrush.config.SkinEntry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entry point. Registers an optional keybind (default: unbound) that opens
 * the dye picker without going through the inventory, and warms up the store.
 *
 * Also runs the helmet-skin applier: skin overrides are a client-side swap of the
 * vanilla profile component on the worn stack, and the server resyncs inventory
 * constantly, so we re-assert the override every tick (a reference-equality check
 * makes the common case a no-op).
 */
public class DyeBrushClient implements ClientModInitializer {

    private static KeyBinding openKey;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("dyebrush", "main"));

    @Override
    public void onInitializeClient() {
        // Touch the store so config loads at startup (and surfaces errors early).
        DyeStore.get();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.dyebrush.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,           // default: unbound; user sets it in Controls
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                openPicker(client);
            }
            applySkinOverrides(client);
        });
    }

    private static void openPicker(MinecraftClient mc) {
        PlayerEntity p = mc.player;
        if (p == null) return;
        mc.setScreen(new DyePickerScreen(mc.currentScreen, currentArmor(p)));
    }

    /** Armor stacks in vanilla inventory order: 36=feet 37=legs 38=chest 39=head. */
    public static ItemStack[] currentArmor(PlayerEntity p) {
        return new ItemStack[] {
                p.getInventory().getStack(36),
                p.getInventory().getStack(37),
                p.getInventory().getStack(38),
                p.getInventory().getStack(39)
        };
    }

    private static void applySkinOverrides(MinecraftClient mc) {
        PlayerEntity p = mc.player;
        if (p == null) return;
        double now = System.nanoTime() / 1_000_000_000.0;
        for (ItemStack stack : currentArmor(p)) {
            if (stack == null || stack.isEmpty()) continue;
            SkinEntry skin = DyeStore.get().skinForStack(stack);
            if (skin == null) continue;
            // frameAt() returns cached instances, so this reference check makes
            // the no-change case (static skins, held frames) a free no-op.
            ProfileComponent override = skin.frameAt(now);
            if (override != null && stack.get(DataComponentTypes.PROFILE) != override) {
                stack.set(DataComponentTypes.PROFILE, override);
            }
        }
    }

    /** Restore a piece's real look right away after its override is cleared. */
    static void restoreOriginalSkin(ItemStack stack, SkinEntry removed) {
        if (stack == null || stack.isEmpty() || removed == null) return;
        ProfileComponent original = removed.original();
        if (original != null) {
            stack.set(DataComponentTypes.PROFILE, original);
        } else {
            stack.remove(DataComponentTypes.PROFILE);
        }
    }
}
