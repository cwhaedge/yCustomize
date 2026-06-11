package com.cwhaedge.ycustomize.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single source of truth: Hypixel item UUID -> DyeEntry.
 *
 * Persisted as JSON in the Fabric config dir so assignments survive relogs and
 * follow the item itself (not an inventory slot). Client-side only; no networking.
 */
public final class DyeStore {

    private static final DyeStore INSTANCE = new DyeStore();
    public static DyeStore get() { return INSTANCE; }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, DyeEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, SkinEntry> skins = new ConcurrentHashMap<>();
    private final Path file;

    private DyeStore() {
        this.file = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("ycustomize.json");
        load();
    }

    // ---- Hypixel item identity ------------------------------------------------

    /**
     * Pull the stable Hypixel item UUID out of an ItemStack.
     *
     * Hypixel stores per-item data in the vanilla custom_data component as NBT.
     * For modern (1.20.5+) clients the attributes sit at the ROOT of custom_data
     * ("uuid", "id", ...); the old "ExtraAttributes" wrapper compound is the 1.8-era
     * layout — we check both for safety. Returns null for stacks that have no such
     * id (e.g. vanilla/un-tagged items), which means they can't be individually dyed.
     */
    public static String hypixelUuid(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom == null) return null;
        NbtCompound nbt = custom.copyNbt();
        String uuid = nbt.getString("uuid", "");
        if (uuid.isEmpty()) {
            uuid = nbt.getCompoundOrEmpty("ExtraAttributes").getString("uuid", "");
        }
        return uuid.isEmpty() ? null : uuid;
    }

    // ---- Lookup / mutation ----------------------------------------------------

    /** The dye assigned to this stack, or null if none / not identifiable. */
    public DyeEntry forStack(ItemStack stack) {
        String id = hypixelUuid(stack);
        return id == null ? null : entries.get(id);
    }

    public DyeEntry forUuid(String uuid) {
        return uuid == null ? null : entries.get(uuid);
    }

    public void assign(ItemStack stack, DyeEntry entry) {
        String id = hypixelUuid(stack);
        if (id == null) return;
        entries.put(id, entry);
        save();
    }

    public void clear(ItemStack stack) {
        String id = hypixelUuid(stack);
        if (id == null) return;
        if (entries.remove(id) != null) save();
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    // ---- Helmet skin overrides --------------------------------------------------

    /** The skin override for this stack, or null if none / not identifiable. */
    public SkinEntry skinForStack(ItemStack stack) {
        String id = hypixelUuid(stack);
        return id == null ? null : skins.get(id);
    }

    public void assignSkin(ItemStack stack, SkinEntry entry) {
        String id = hypixelUuid(stack);
        if (id == null) return;
        skins.put(id, entry);
        save();
    }

    /** Removes the override and returns it (so the caller can restore the original look). */
    public SkinEntry clearSkin(ItemStack stack) {
        String id = hypixelUuid(stack);
        if (id == null) return null;
        SkinEntry removed = skins.remove(id);
        if (removed != null) save();
        return removed;
    }

    // ---- Persistence ----------------------------------------------------------

    private void load() {
        try {
            if (!Files.exists(file)) return;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("dyes") || root.has("skins")) {
                // v2 format: { "dyes": {uuid: DyeEntry}, "skins": {uuid: SkinEntry} }
                if (root.has("dyes")) {
                    for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("dyes").entrySet()) {
                        DyeEntry entry = GSON.fromJson(e.getValue(), DyeEntry.class);
                        if (entry != null) entries.put(e.getKey(), entry);
                    }
                }
                if (root.has("skins")) {
                    for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("skins").entrySet()) {
                        SkinEntry entry = GSON.fromJson(e.getValue(), SkinEntry.class);
                        if (entry != null) skins.put(e.getKey(), entry);
                    }
                }
            } else {
                // v1 format: flat {uuid: DyeEntry} — migrate on next save.
                for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                    DyeEntry entry = GSON.fromJson(e.getValue(), DyeEntry.class);
                    if (entry != null) entries.put(e.getKey(), entry);
                }
            }
        } catch (Exception ex) {
            System.err.println("[yCustomize] Failed to load config: " + ex.getMessage());
        }
    }

    public void save() {
        try {
            JsonObject dyes = new JsonObject();
            for (Map.Entry<String, DyeEntry> e : entries.entrySet()) {
                dyes.add(e.getKey(), GSON.toJsonTree(e.getValue()));
            }
            JsonObject skinObj = new JsonObject();
            for (Map.Entry<String, SkinEntry> e : skins.entrySet()) {
                skinObj.add(e.getKey(), GSON.toJsonTree(e.getValue()));
            }
            JsonObject root = new JsonObject();
            root.add("dyes", dyes);
            root.add("skins", skinObj);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.err.println("[yCustomize] Failed to save config: " + ex.getMessage());
        }
    }
}
