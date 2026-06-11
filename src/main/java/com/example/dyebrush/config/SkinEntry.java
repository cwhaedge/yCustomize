package com.example.dyebrush.config;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.serialization.JsonOps;
import net.minecraft.component.type.ProfileComponent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A saved helmet-skin override for one armor piece (keyed by its Hypixel item UUID).
 *
 * Hypixel helmets are player-head items; their look is the skin texture inside the
 * vanilla {@code minecraft:profile} component. We copy that component from a source
 * head ("Celestial Wither Goggles") onto the worn one ("Spirit Mask") client-side.
 *
 * The profile is persisted as JSON via the vanilla codec so it survives restarts.
 * We also keep the piece's ORIGINAL profile so Clear can restore the real look
 * without waiting for a server resync.
 */
public final class SkinEntry {

    /** Display name of the head the skin was copied from (for the UI). */
    public String sourceName = "";

    /** Serialized ProfileComponent of the override skin (static skins). */
    public JsonElement profile;

    /** ANIMATED skins: one base64 texture per frame, each shown frameTicks ticks. */
    public String[] frameTextures;
    public int frameTicks = 2;

    /** Serialized ProfileComponent the piece had before we touched it (may be null). */
    public JsonElement originalProfile;

    private transient ProfileComponent cached;
    private transient ProfileComponent cachedOriginal;
    private transient ProfileComponent[] cachedFrames;

    public static SkinEntry of(String sourceName, ProfileComponent override, ProfileComponent original) {
        SkinEntry e = new SkinEntry();
        e.sourceName = sourceName == null ? "" : sourceName;
        e.cached = override;
        e.profile = encode(override);
        e.cachedOriginal = original;
        e.originalProfile = encode(original);
        return e;
    }

    public static SkinEntry ofAnimated(String sourceName, String[] frames, int ticksPerFrame,
                                       ProfileComponent original) {
        SkinEntry e = new SkinEntry();
        e.sourceName = sourceName == null ? "" : sourceName;
        e.frameTextures = frames;
        e.frameTicks = Math.max(1, ticksPerFrame);
        e.cachedOriginal = original;
        e.originalProfile = encode(original);
        return e;
    }

    public boolean isAnimated() {
        return frameTextures != null && frameTextures.length > 1;
    }

    /** The override skin component, or null if it can't be decoded. */
    public ProfileComponent component() {
        if (cached == null) {
            if (profile != null) {
                cached = decode(profile);
            } else if (frameTextures != null && frameTextures.length > 0) {
                cached = fromTexture(frameTextures[0]);
            }
        }
        return cached;
    }

    /**
     * The component to show right now. Static skins ignore time; animated ones
     * step through their frames (20 ticks/sec). Frame components are built once
     * and reused, so callers can use reference equality to skip redundant sets.
     */
    public ProfileComponent frameAt(double timeSeconds) {
        if (!isAnimated()) return component();
        if (cachedFrames == null) {
            ProfileComponent[] arr = new ProfileComponent[frameTextures.length];
            for (int i = 0; i < arr.length; i++) arr[i] = fromTexture(frameTextures[i]);
            cachedFrames = arr;
        }
        int idx = (int) (((long) (timeSeconds * 20.0 / frameTicks)) % cachedFrames.length);
        return cachedFrames[idx];
    }

    /** The piece's pre-override component, or null if it had none. */
    public ProfileComponent original() {
        if (cachedOriginal == null) cachedOriginal = decode(originalProfile);
        return cachedOriginal;
    }

    /**
     * Build a profile component from a raw base64 texture value (skin database path —
     * for items the player doesn't own). Unsigned textures render fine on heads; the
     * UUID is derived from the texture so identical skins compare equal.
     */
    public static ProfileComponent fromTexture(String textureValue) {
        PropertyMap props = new PropertyMap(ImmutableMultimap.of(
                "textures", new Property("textures", textureValue)));
        GameProfile gp = new GameProfile(
                UUID.nameUUIDFromBytes(("dyebrush:" + textureValue).getBytes(StandardCharsets.UTF_8)),
                "dyebrush", props);
        return ProfileComponent.ofStatic(gp);
    }

    private static JsonElement encode(ProfileComponent comp) {
        if (comp == null) return null;
        return ProfileComponent.CODEC.encodeStart(JsonOps.INSTANCE, comp).result().orElse(null);
    }

    private static ProfileComponent decode(JsonElement el) {
        if (el == null) return null;
        return ProfileComponent.CODEC.parse(JsonOps.INSTANCE, el).result().orElse(null);
    }
}
