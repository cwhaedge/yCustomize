package com.example.dyebrush.client;

import com.example.dyebrush.config.DyeEntry;
import com.example.dyebrush.config.DyeStore;
import com.example.dyebrush.config.HypixelDyes;
import com.example.dyebrush.config.SkinDatabase;
import com.example.dyebrush.config.SkinEntry;
import com.example.dyebrush.render.DyeRenderState;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dye picker: pick an equipped piece (shown by its actual item name), choose a
 * hex / named / animated dye or a helmet skin, and watch a live preview of your
 * character on the right while you fiddle.
 *
 * Named dyes use a dropdown with a color swatch per entry. Helmet skins use a
 * search box over the full Hypixel item database (plus heads in your inventory),
 * so you can apply skins of items you don't own.
 */
public class DyePickerScreen extends Screen {

    private final Screen parent;

    // The four armor stacks, captured when the screen opens.
    private final ItemStack[] armor = new ItemStack[4]; // 0=feet 1=legs 2=chest 3=head (vanilla order)
    private static final String[] SLOT_NAMES = {"Boots", "Leggings", "Chestplate", "Helmet"};
    private static final int HEAD_SLOT = 3;

    private int selectedSlot = HEAD_SLOT;

    // Feedback line shown under the title (Screen.title is final in 1.21.9+).
    private Text status = Text.empty();

    private enum UiMode { HEX, NAMED, ANIMATED, SKIN }
    private UiMode mode = UiMode.HEX;

    private TextFieldWidget hexField;
    private ButtonWidget namedButton;
    private TextFieldWidget skinSearch;
    private ButtonWidget skinSelectButton;

    private final List<String> dyeNames = new ArrayList<>();
    private int namedIndex = 0;

    // Currently chosen skin source: a static profile (inventory / static DB entry)
    // OR an animated frame set (animated DB entry) — exactly one is non-null.
    private String selectedSkinLabel = null;
    private ProfileComponent selectedSkinProfile = null;
    private String[] selectedSkinFrames = null;
    private int selectedSkinTicks = 2;

    /** Heads found in the player's inventory (usable as skin sources directly). */
    private record SkinOption(String label, ProfileComponent profile) {}
    private final List<SkinOption> inventoryHeads = new ArrayList<>();

    // ---- dropdown overlay (hand-rolled; rendered on top, input handled first) ----
    private record DropdownEntry(String label, int swatch, Runnable onSelect) {}
    private final List<DropdownEntry> dropdownEntries = new ArrayList<>();
    private boolean dropdownOpen = false;
    private int dropdownX, dropdownY;
    private int dropdownScroll = 0;
    private static final int ROW_H = 14;
    private static final int VISIBLE_ROWS = 8;

    // Animated default: a simple rainbow loop the user can keep or ignore.
    private static final int[] DEFAULT_ANIM = {
            0xFF0000, 0xFF7F00, 0xFFFF00, 0x00FF00, 0x0000FF, 0x4B0082, 0x9400D3
    };

    private int colX, colW, baseY;

    public DyePickerScreen(Screen parent, ItemStack[] currentArmor) {
        super(Text.literal("DyeBrush"));
        this.parent = parent;
        if (currentArmor != null) {
            System.arraycopy(currentArmor, 0, this.armor, 0, Math.min(4, currentArmor.length));
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        baseY = this.height / 4;
        colX = cx - 154;
        colW = 200;

        SkinDatabase.ensureLoaded();
        collectInventoryHeads();

        // Refresh the dye list each (re)init — the DB may have finished loading
        // since the screen opened, and resize re-runs init().
        String keepSelected = dyeNames.isEmpty() ? null : dyeNames.get(namedIndex);
        dyeNames.clear();
        dyeNames.addAll(HypixelDyes.names());
        dyeNames.sort(String.CASE_INSENSITIVE_ORDER);
        namedIndex = Math.max(0, dyeNames.indexOf(keepSelected));

        // Piece selector — labelled with the actual item name, not just the slot.
        addDrawableChild(CyclingButtonWidget.builder(this::pieceLabel, selectedSlot)
                .values(0, 1, 2, 3)
                .omitKeyText()
                .build(colX, baseY, colW, 20, Text.literal("Piece"),
                        (btn, val) -> { selectedSlot = val; closeDropdown(); updatePreview(); }));

        // Mode selector
        addDrawableChild(CyclingButtonWidget.builder((UiMode m) -> Text.literal(switch (m) {
                    case HEX -> "Mode: Hex";
                    case NAMED -> "Mode: Named Dye";
                    case ANIMATED -> "Mode: Animated";
                    case SKIN -> "Mode: Helmet Skin";
                }), mode)
                .values(UiMode.HEX, UiMode.NAMED, UiMode.ANIMATED, UiMode.SKIN)
                .omitKeyText()
                .build(colX, baseY + 26, colW, 20, Text.literal("Mode"),
                        (btn, val) -> {
                            mode = val;
                            closeDropdown();
                            updateWidgetVisibility();
                            updatePreview();
                            if (mode == UiMode.SKIN) SkinDatabase.ensureLoaded();
                        }));

        // Hex input (HEX mode)
        hexField = new TextFieldWidget(this.textRenderer, colX, baseY + 52, colW, 20, Text.literal("Hex"));
        hexField.setMaxLength(7);
        hexField.setText("#FFFFFF");
        hexField.setChangedListener(s -> updatePreview());
        addDrawableChild(hexField);

        // Named-dye dropdown opener (NAMED mode) — swatch is drawn next to it in render().
        namedButton = ButtonWidget.builder(namedLabel(), btn -> toggleNamedDropdown())
                .dimensions(colX, baseY + 52, colW, 20).build();
        addDrawableChild(namedButton);

        // Skin search box + selection opener (SKIN mode)
        skinSearch = new TextFieldWidget(this.textRenderer, colX, baseY + 52, colW, 20,
                Text.literal("Search skins"));
        skinSearch.setMaxLength(64);
        skinSearch.setPlaceholder(Text.literal("Search any SkyBlock item…").formatted(Formatting.DARK_GRAY));
        skinSearch.setChangedListener(s -> { if (mode == UiMode.SKIN) openSkinDropdown(); });
        addDrawableChild(skinSearch);

        skinSelectButton = ButtonWidget.builder(skinLabel(), btn -> toggleSkinDropdown())
                .dimensions(colX, baseY + 78, colW, 20).build();
        addDrawableChild(skinSelectButton);

        // Apply / Clear / Done
        addDrawableChild(ButtonWidget.builder(Text.literal("Apply"), btn -> apply())
                .dimensions(colX, baseY + 110, 96, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), btn -> clearSlot())
                .dimensions(colX + 104, baseY + 110, 96, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> close())
                .dimensions(colX, baseY + 136, colW, 20).build());

        updateWidgetVisibility();
        updatePreview();
    }

    // ---- labels -------------------------------------------------------------------

    private Text pieceLabel(int slot) {
        ItemStack s = armor[slot];
        if (s == null || s.isEmpty()) {
            return Text.literal(SLOT_NAMES[slot] + ": ").append(
                    Text.literal("(empty)").formatted(Formatting.GRAY));
        }
        return Text.literal(SLOT_NAMES[slot] + ": ").append(s.getName());
    }

    private Text namedLabel() {
        return Text.literal("Dye: " + (dyeNames.isEmpty() ? "—" : dyeNames.get(namedIndex)) + " ▾");
    }

    private Text skinLabel() {
        return Text.literal("Skin: " + (selectedSkinLabel == null ? "(pick one)" : selectedSkinLabel) + " ▾");
    }

    private void collectInventoryHeads() {
        inventoryHeads.clear();
        if (this.client == null || this.client.player == null) return;
        var inv = this.client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            ProfileComponent profile = s.get(DataComponentTypes.PROFILE);
            if (profile == null) continue;
            inventoryHeads.add(new SkinOption(s.getName().getString(), profile));
        }
    }

    private void updateWidgetVisibility() {
        hexField.visible = mode == UiMode.HEX;
        namedButton.visible = mode == UiMode.NAMED;
        skinSearch.visible = mode == UiMode.SKIN;
        skinSelectButton.visible = mode == UiMode.SKIN;
    }

    // ---- dropdown ------------------------------------------------------------------

    private void toggleNamedDropdown() {
        if (dropdownOpen) { closeDropdown(); return; }
        dropdownEntries.clear();
        for (int i = 0; i < dyeNames.size(); i++) {
            final int idx = i;
            String name = dyeNames.get(i);
            String label = HypixelDyes.isAnimatedDisplay(name) ? name + " ✨" : name;
            dropdownEntries.add(new DropdownEntry(label, HypixelDyes.resolveDisplay(name), () -> {
                namedIndex = idx;
                namedButton.setMessage(namedLabel());
                updatePreview();
            }));
        }
        openDropdown(colX, baseY + 72);
    }

    private void toggleSkinDropdown() {
        if (dropdownOpen) closeDropdown();
        else openSkinDropdown();
    }

    private void openSkinDropdown() {
        dropdownEntries.clear();
        String query = skinSearch.getText();
        String q = query == null ? "" : query.trim().toLowerCase();

        for (SkinOption opt : inventoryHeads) {
            if (!q.isEmpty() && !opt.label().toLowerCase().contains(q)) continue;
            dropdownEntries.add(new DropdownEntry("✦ " + opt.label(), -1, () -> {
                selectedSkinLabel = opt.label();
                selectedSkinProfile = opt.profile();
                selectedSkinFrames = null;
                skinSelectButton.setMessage(skinLabel());
            }));
        }

        if (SkinDatabase.isReady()) {
            // Animated skins first — they're rarer and usually what's being hunted.
            for (Map.Entry<String, SkinDatabase.AnimatedSkin> e : SkinDatabase.searchAnimated(q, 25)) {
                String name = e.getKey();
                SkinDatabase.AnimatedSkin anim = e.getValue();
                dropdownEntries.add(new DropdownEntry(name + " ✨", -1, () -> {
                    selectedSkinLabel = name;
                    selectedSkinProfile = null;
                    selectedSkinFrames = anim.frames();
                    selectedSkinTicks = anim.ticksPerFrame();
                    skinSelectButton.setMessage(skinLabel());
                }));
            }
            for (Map.Entry<String, String> e : SkinDatabase.search(q, 40)) {
                String name = e.getKey();
                String texture = e.getValue();
                dropdownEntries.add(new DropdownEntry(name, -1, () -> {
                    selectedSkinLabel = name;
                    selectedSkinProfile = SkinEntry.fromTexture(texture);
                    selectedSkinFrames = null;
                    skinSelectButton.setMessage(skinLabel());
                }));
            }
        } else {
            SkinDatabase.ensureLoaded();
            dropdownEntries.add(new DropdownEntry(SkinDatabase.statusLine(), -1, () -> {}));
        }
        openDropdown(colX, baseY + 98);
    }

    private void openDropdown(int x, int y) {
        dropdownX = x;
        dropdownY = y;
        dropdownScroll = 0;
        dropdownOpen = !dropdownEntries.isEmpty();
    }

    private void closeDropdown() {
        dropdownOpen = false;
    }

    private int dropdownHeight() {
        return Math.min(VISIBLE_ROWS, dropdownEntries.size()) * ROW_H + 2;
    }

    private boolean inDropdown(double mx, double my) {
        return dropdownOpen
                && mx >= dropdownX && mx < dropdownX + colW
                && my >= dropdownY && my < dropdownY + dropdownHeight();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (dropdownOpen) {
            if (inDropdown(click.x(), click.y())) {
                int row = (int) ((click.y() - dropdownY - 1) / ROW_H) + dropdownScroll;
                if (row >= 0 && row < dropdownEntries.size()) {
                    dropdownEntries.get(row).onSelect().run();
                    closeDropdown();
                }
                return true;
            }
            // Click elsewhere closes the dropdown; let the click through to widgets.
            closeDropdown();
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (inDropdown(mx, my)) {
            int maxScroll = Math.max(0, dropdownEntries.size() - VISIBLE_ROWS);
            dropdownScroll = Math.max(0, Math.min(maxScroll, dropdownScroll - (int) Math.signum(vAmount)));
            return true;
        }
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    // ---- dye logic -------------------------------------------------------------------

    private ItemStack selected() {
        return armor[selectedSlot];
    }

    /** The dye the current controls describe, or null if not a (valid) dye. */
    private DyeEntry pendingDye() {
        switch (mode) {
            case HEX -> {
                Integer hex = parseHex(hexField == null ? null : hexField.getText());
                return hex == null ? null : DyeEntry.ofStatic(hex);
            }
            case NAMED -> {
                return dyeNames.isEmpty() ? null : DyeEntry.ofNamed(dyeNames.get(namedIndex));
            }
            case ANIMATED -> {
                return DyeEntry.ofAnimated(DEFAULT_ANIM, 5.0f);
            }
            default -> { return null; }
        }
    }

    /** Push the pending (unsaved) dye into the render preview slot. */
    private void updatePreview() {
        ItemStack stack = selected();
        String uuid = stack == null ? null : DyeStore.hypixelUuid(stack);
        DyeEntry pending = mode == UiMode.SKIN ? null : pendingDye();
        if (uuid == null || pending == null) {
            DyeRenderState.clearPreview();
        } else {
            DyeRenderState.setPreview(uuid, pending);
        }
    }

    private void apply() {
        ItemStack stack = selected();
        if (stack == null || stack.isEmpty()) {
            status = Text.literal("That slot is empty").formatted(Formatting.RED);
            return;
        }
        if (DyeStore.hypixelUuid(stack) == null) {
            status = Text.literal("That piece has no Hypixel ID — can't dye it")
                    .formatted(Formatting.RED);
            return;
        }

        if (mode == UiMode.SKIN) {
            applySkin(stack);
            return;
        }

        DyeEntry entry = pendingDye();
        if (entry == null) {
            status = Text.literal("Invalid color — use #RRGGBB").formatted(Formatting.RED);
            return;
        }
        DyeStore.get().assign(stack, entry);
        status = Text.literal("Applied to ").append(stack.getName()).formatted(Formatting.GREEN);
    }

    private void applySkin(ItemStack stack) {
        if (selectedSlot != HEAD_SLOT) {
            status = Text.literal("Skins only work on helmets").formatted(Formatting.RED);
            return;
        }
        if (selectedSkinProfile == null && selectedSkinFrames == null) {
            status = Text.literal("Pick a skin first").formatted(Formatting.RED);
            return;
        }

        // Preserve the true original look: if we already overrode this piece,
        // keep the original captured back then instead of our own override.
        SkinEntry existing = DyeStore.get().skinForStack(stack);
        ProfileComponent original = existing != null
                ? existing.original()
                : stack.get(DataComponentTypes.PROFILE);

        SkinEntry entry = selectedSkinFrames != null
                ? SkinEntry.ofAnimated(selectedSkinLabel, selectedSkinFrames, selectedSkinTicks, original)
                : SkinEntry.of(selectedSkinLabel, selectedSkinProfile, original);
        DyeStore.get().assignSkin(stack, entry);
        status = Text.literal("Skin \"" + selectedSkinLabel + "\" applied to ")
                .append(stack.getName()).formatted(Formatting.GREEN);
    }

    private void clearSlot() {
        ItemStack stack = selected();
        if (stack == null || stack.isEmpty()) return;
        DyeStore.get().clear(stack);
        SkinEntry removedSkin = DyeStore.get().clearSkin(stack);
        DyeBrushClient.restoreOriginalSkin(stack, removedSkin);
        DyeRenderState.clearPreview();
        status = Text.literal("Cleared ").append(stack.getName());
    }

    private static Integer parseHex(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        try {
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- render -----------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Screen.render() already draws the blurred background in 1.21+;
        // calling renderBackground() here too trips "Can only blur once per frame".
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, baseY - 36, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.status,
                this.width / 2, baseY - 24, 0xFFFFFFFF);

        // Swatch of the currently selected named dye, next to the dropdown button.
        if (mode == UiMode.NAMED && !dyeNames.isEmpty()) {
            int sw = HypixelDyes.resolveDisplay(dyeNames.get(namedIndex));
            int sx = colX + colW + 6;
            int sy = baseY + 52;
            ctx.fill(sx - 1, sy - 1, sx + 21, sy + 21, 0xFF000000);
            ctx.fill(sx, sy, sx + 20, sy + 20, 0xFF000000 | sw);
        }
        if (mode == UiMode.ANIMATED) {
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal("Rainbow loop (edit config for custom stops)").formatted(Formatting.GRAY),
                    colX, baseY + 58, 0xFFAAAAAA);
        }

        // Live character preview on the right — renders the real worn stacks,
        // so dye previews and applied skins show up immediately.
        if (this.client != null && this.client.player != null) {
            int x1 = this.width / 2 + 56;
            int x2 = x1 + 90;
            int y1 = baseY;
            int y2 = y1 + 156;
            ctx.fill(x1 - 2, y1 - 2, x2 + 2, y2 + 2, 0x60000000);
            InventoryScreen.drawEntity(ctx, x1, y1, x2, y2, 60, 0.0625F,
                    mouseX, mouseY, this.client.player);
        }

        // Dropdown overlay goes on top of everything.
        if (dropdownOpen) {
            int h = dropdownHeight();
            ctx.fill(dropdownX - 1, dropdownY - 1, dropdownX + colW + 1, dropdownY + h + 1, 0xFFFFFFFF);
            ctx.fill(dropdownX, dropdownY, dropdownX + colW, dropdownY + h, 0xF0101010);
            int shown = Math.min(VISIBLE_ROWS, dropdownEntries.size());
            for (int r = 0; r < shown; r++) {
                int idx = r + dropdownScroll;
                if (idx >= dropdownEntries.size()) break;
                DropdownEntry e = dropdownEntries.get(idx);
                int ry = dropdownY + 1 + r * ROW_H;
                boolean hover = mouseX >= dropdownX && mouseX < dropdownX + colW
                        && mouseY >= ry && mouseY < ry + ROW_H;
                if (hover) ctx.fill(dropdownX, ry, dropdownX + colW, ry + ROW_H, 0x40FFFFFF);

                int textX = dropdownX + 4;
                if (e.swatch() >= 0) {
                    ctx.fill(dropdownX + 3, ry + 2, dropdownX + 13, ry + 12, 0xFF000000);
                    ctx.fill(dropdownX + 4, ry + 3, dropdownX + 12, ry + 11, 0xFF000000 | e.swatch());
                    textX = dropdownX + 17;
                }
                ctx.drawTextWithShadow(this.textRenderer, Text.literal(e.label()),
                        textX, ry + 3, 0xFFFFFFFF);
            }
            if (dropdownEntries.size() > VISIBLE_ROWS) {
                ctx.drawTextWithShadow(this.textRenderer,
                        Text.literal((dropdownScroll + 1) + "–"
                                + Math.min(dropdownScroll + VISIBLE_ROWS, dropdownEntries.size())
                                + " / " + dropdownEntries.size()).formatted(Formatting.GRAY),
                        dropdownX + colW + 4, dropdownY + 2, 0xFFAAAAAA);
            }
        }
    }

    @Override
    public void close() {
        DyeRenderState.clearPreview();
        if (this.client != null) this.client.setScreen(parent);
    }
}
