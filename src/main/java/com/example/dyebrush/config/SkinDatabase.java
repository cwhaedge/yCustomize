package com.example.dyebrush.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * All SkyBlock helmet skins, whether you own the item or not.
 *
 * Hypixel's public items resource (no API key needed) lists every SkyBlock item;
 * skull-based ones carry their skin texture in a "skin" field. We download it once
 * in the background, keep only item name -> texture value, and cache that slim map
 * in the config dir so later launches don't hit the network at all.
 */
public final class SkinDatabase {

    private SkinDatabase() {}

    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";

    /**
     * The NotEnoughUpdates community item repo. Hypixel's own API skips most
     * cosmetic items (helmet skins like the Panda Spirit Mask variants live there,
     * not in the items resource), so we merge this in as a second source.
     */
    private static final String NEU_ZIP_URL =
            "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/refs/heads/master.zip";

    /** Bump to invalidate caches written by older versions (forces a refetch). */
    private static final String CACHE_VERSION = "5";
    private static final String CACHE_VERSION_KEY = "__dyebrush_cache_version";

    /** An animated skull skin: one texture per frame, each shown for ticksPerFrame. */
    public record AnimatedSkin(String[] frames, int ticksPerFrame) {}

    /**
     * Animated skins from NEU constants/animatedskulls.json (741 entries: helmet
     * skins, dungeon masks, etc.), keyed by a display name derived from the id.
     */
    private static final Map<String, AnimatedSkin> ANIM_SKINS =
            new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * Dye display name -> color ramp. Authoritative source is NEU's
     * constants/dyes.json: "static" has exact hexes; "animated" has the full
     * frame-by-frame sequence (1 frame = 1 game tick, so period = frames / 20s).
     * Lore-derived colors are only a fallback — animated dyes' lore shows two
     * adjacent animation frames, not the real range.
     */
    private static final Map<String, HypixelDyes.DyeDef> DYE_RAMPS =
            new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    /** Item display name -> base64 texture value. Sorted, case-insensitive. */
    private static final Map<String, String> SKINS =
            new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    private static volatile boolean ready = false;
    private static volatile boolean loading = false;
    private static volatile String error = null;
    private static volatile String progress = "Downloading skin database…";

    private static Path cacheFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("dyebrush_skindb.json");
    }

    private static Path dyeCacheFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("dyebrush_dyedb.json");
    }

    private static Path animSkinCacheFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("dyebrush_animskindb.json");
    }

    public static boolean isReady() { return ready; }

    /** Human-readable line for the UI while the DB isn't usable yet. */
    public static String statusLine() {
        if (ready) return SKINS.size() + " skins loaded";
        if (error != null) return "Skin DB failed: " + error;
        return progress;
    }

    /** Kick off loading (cache first, network fallback). Safe to call repeatedly. */
    public static void ensureLoaded() {
        if (ready || loading) return;
        synchronized (SkinDatabase.class) {
            if (ready || loading) return;
            loading = true;
        }
        Thread t = new Thread(SkinDatabase::loadBlocking, "DyeBrush-SkinDB");
        t.setDaemon(true);
        t.start();
    }

    /** Case-insensitive substring search over item names. */
    public static List<Map.Entry<String, String>> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : SKINS.entrySet()) {
            if (q.isEmpty() || e.getKey().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /** Same search over the animated skins. */
    public static List<Map.Entry<String, AnimatedSkin>> searchAnimated(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Map.Entry<String, AnimatedSkin>> out = new ArrayList<>();
        for (Map.Entry<String, AnimatedSkin> e : ANIM_SKINS.entrySet()) {
            if (q.isEmpty() || e.getKey().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    // ---- loading ----------------------------------------------------------------

    private static void loadBlocking() {
        try {
            if (loadCache()) {
                HypixelDyes.registerAll(DYE_RAMPS);
                ready = true;
                return;
            }
            progress = "Downloading Hypixel items…";
            fetchFromHypixel();
            boolean neuOk = true;
            try {
                progress = "Downloading NEU repo (skins + dyes, one-time)…";
                fetchFromNeuRepo();
            } catch (Exception ex) {
                // Cosmetics/dyes are a bonus — keep the Hypixel data if NEU is unreachable.
                neuOk = false;
                System.err.println("[DyeBrush] NEU repo fetch failed (cosmetic skins/dyes missing): " + ex);
            }
            // Only cache complete results — caching a partial fetch would freeze
            // the missing dyes/cosmetics in place forever; without a cache we
            // simply retry on next launch.
            if (neuOk) saveCache();
            HypixelDyes.registerAll(DYE_RAMPS);
            ready = true;
        } catch (Exception ex) {
            error = ex.getMessage();
            System.err.println("[DyeBrush] Skin database load failed: " + ex);
        } finally {
            loading = false;
        }
    }

    private static boolean loadCache() {
        try {
            Path f = cacheFile();
            if (!Files.exists(f)) return false;
            JsonObject root = JsonParser.parseString(
                    Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement ver = root.get(CACHE_VERSION_KEY);
            if (ver == null || !CACHE_VERSION.equals(ver.getAsString())) {
                return false; // older cache (e.g. pre-NEU) — refetch everything
            }
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getKey().equals(CACHE_VERSION_KEY)) continue;
                SKINS.put(e.getKey(), e.getValue().getAsString());
            }
            loadDyeCache();
            loadAnimSkinCache();
            return !SKINS.isEmpty();
        } catch (Exception ex) {
            System.err.println("[DyeBrush] Skin DB cache unreadable, refetching: " + ex.getMessage());
            return false;
        }
    }

    private static void loadAnimSkinCache() {
        try {
            Path f = animSkinCacheFile();
            if (!Files.exists(f)) return;
            JsonObject root = JsonParser.parseString(
                    Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getKey().equals(CACHE_VERSION_KEY)) continue;
                JsonObject def = e.getValue().getAsJsonObject();
                var arr = def.getAsJsonArray("frames");
                String[] frames = new String[arr.size()];
                for (int i = 0; i < frames.length; i++) frames[i] = arr.get(i).getAsString();
                int ticks = def.has("ticks") ? Math.max(1, def.get("ticks").getAsInt()) : 2;
                if (frames.length > 0) ANIM_SKINS.put(e.getKey(), new AnimatedSkin(frames, ticks));
            }
        } catch (Exception ex) {
            System.err.println("[DyeBrush] Animated skin cache unreadable: " + ex.getMessage());
        }
    }

    private static void loadDyeCache() {
        try {
            Path f = dyeCacheFile();
            if (!Files.exists(f)) return;
            JsonObject root = JsonParser.parseString(
                    Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getKey().equals(CACHE_VERSION_KEY)) continue;
                JsonObject def = e.getValue().getAsJsonObject();
                var arr = def.getAsJsonArray("stops");
                int[] stops = new int[arr.size()];
                for (int i = 0; i < stops.length; i++) {
                    stops[i] = Integer.parseInt(arr.get(i).getAsString(), 16);
                }
                float period = def.has("period") ? def.get("period").getAsFloat() : 4f;
                if (stops.length > 0) {
                    DYE_RAMPS.put(e.getKey(), new HypixelDyes.DyeDef(stops, period));
                }
            }
        } catch (Exception ex) {
            System.err.println("[DyeBrush] Dye DB cache unreadable: " + ex.getMessage());
        }
    }

    private static void saveCache() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty(CACHE_VERSION_KEY, CACHE_VERSION);
            for (Map.Entry<String, String> e : SKINS.entrySet()) {
                root.addProperty(e.getKey(), e.getValue());
            }
            Files.createDirectories(cacheFile().getParent());
            Files.writeString(cacheFile(), root.toString(), StandardCharsets.UTF_8);

            JsonObject dyes = new JsonObject();
            dyes.addProperty(CACHE_VERSION_KEY, CACHE_VERSION);
            for (Map.Entry<String, HypixelDyes.DyeDef> e : DYE_RAMPS.entrySet()) {
                JsonObject def = new JsonObject();
                def.addProperty("period", e.getValue().periodSeconds());
                var arr = new com.google.gson.JsonArray();
                for (int stop : e.getValue().stops()) arr.add(String.format("%06X", stop));
                def.add("stops", arr);
                dyes.add(e.getKey(), def);
            }
            Files.writeString(dyeCacheFile(), dyes.toString(), StandardCharsets.UTF_8);

            JsonObject anim = new JsonObject();
            anim.addProperty(CACHE_VERSION_KEY, CACHE_VERSION);
            for (Map.Entry<String, AnimatedSkin> e : ANIM_SKINS.entrySet()) {
                JsonObject def = new JsonObject();
                def.addProperty("ticks", e.getValue().ticksPerFrame());
                var arr = new com.google.gson.JsonArray();
                for (String frame : e.getValue().frames()) arr.add(frame);
                def.add("frames", arr);
                anim.add(e.getKey(), def);
            }
            Files.writeString(animSkinCacheFile(), anim.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            System.err.println("[DyeBrush] Failed to write skin DB cache: " + ex.getMessage());
        }
    }

    private static void fetchFromHypixel() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(ITEMS_URL))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "DyeBrush/1.0")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " from Hypixel items API");
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        for (JsonElement el : root.getAsJsonArray("items")) {
            JsonObject item = el.getAsJsonObject();
            if (!item.has("skin") || !item.has("name")) continue;
            String value = textureValue(item.get("skin"));
            if (value == null || value.isEmpty()) continue;
            String name = item.get("name").getAsString().replaceAll("§.", "").trim();
            if (!name.isEmpty()) SKINS.put(name, value);
        }
        if (SKINS.isEmpty()) {
            throw new IllegalStateException("items API returned no skinned items");
        }
    }

    /**
     * Stream the NEU repo zip and merge every skull item's texture by display name.
     * Item JSONs carry the texture inside an "nbttag" string —
     * {@code SkullOwner:{...textures:[{Value:"base64..."}]}} — extracted by regex.
     * Hypixel API entries win on name collisions (putIfAbsent).
     */
    private static void fetchFromNeuRepo() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(NEU_ZIP_URL))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "DyeBrush/1.0")
                .GET()
                .build();
        HttpResponse<java.io.InputStream> resp =
                client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " from NEU repo");
        }

        java.util.regex.Pattern texture =
                java.util.regex.Pattern.compile("Value:\"([A-Za-z0-9+/=]+)\"");
        java.util.regex.Pattern hex =
                java.util.regex.Pattern.compile("#([0-9A-Fa-f]{6})");
        int added = 0;

        // Collected during the zip pass, combined afterwards (entry order varies).
        Map<String, String> dyeDisplayNames = new java.util.HashMap<>();   // DYE_X -> "X Dye"
        Map<String, int[]> dyeLoreStops = new java.util.HashMap<>();       // lore fallback
        Map<String, int[]> dyeAnimFrames = new java.util.HashMap<>();      // constants: animated
        Map<String, Integer> dyeStaticColors = new java.util.HashMap<>();  // constants: static

        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(resp.body())) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;

                // The authoritative dye data: exact static hexes + full animation
                // frame sequences (one frame per game tick).
                if (name.endsWith("/constants/dyes.json")) {
                    try {
                        JsonObject root = JsonParser.parseString(
                                new String(zin.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        if (root.has("animated")) {
                            for (Map.Entry<String, JsonElement> e
                                    : root.getAsJsonObject("animated").entrySet()) {
                                var arr = e.getValue().getAsJsonArray();
                                int[] frames = new int[arr.size()];
                                for (int i = 0; i < frames.length; i++) {
                                    frames[i] = Integer.parseInt(
                                            arr.get(i).getAsString().replace("#", ""), 16);
                                }
                                if (frames.length > 0) dyeAnimFrames.put(e.getKey(), frames);
                            }
                        }
                        if (root.has("static")) {
                            for (Map.Entry<String, JsonElement> e
                                    : root.getAsJsonObject("static").entrySet()) {
                                dyeStaticColors.put(e.getKey(), Integer.parseInt(
                                        e.getValue().getAsString().replace("#", ""), 16));
                            }
                        }
                    } catch (Exception ex) {
                        System.err.println("[DyeBrush] Couldn't parse NEU dyes.json: " + ex.getMessage());
                    }
                    continue;
                }

                // Animated skull skins: id -> { ticks, textures:["uuid:base64", ...] }.
                if (name.endsWith("/constants/animatedskulls.json")) {
                    try {
                        JsonObject root = JsonParser.parseString(
                                new String(zin.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        for (Map.Entry<String, JsonElement> e
                                : root.getAsJsonObject("skins").entrySet()) {
                            JsonObject def = e.getValue().getAsJsonObject();
                            if (!def.has("textures")) continue;
                            var arr = def.getAsJsonArray("textures");
                            String[] frames = new String[arr.size()];
                            for (int i = 0; i < frames.length; i++) {
                                String t = arr.get(i).getAsString();
                                int sep = t.indexOf(':');
                                frames[i] = sep >= 0 ? t.substring(sep + 1) : t;
                            }
                            int ticks = def.has("ticks") ? Math.max(1, def.get("ticks").getAsInt()) : 2;
                            if (frames.length > 0) {
                                ANIM_SKINS.put(deriveSkinName(e.getKey()), new AnimatedSkin(frames, ticks));
                            }
                        }
                    } catch (Exception ex) {
                        System.err.println("[DyeBrush] Couldn't parse NEU animatedskulls.json: " + ex.getMessage());
                    }
                    continue;
                }

                if (!name.contains("/items/") || !name.endsWith(".json")) continue;
                String json = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                try {
                    JsonObject item = JsonParser.parseString(json).getAsJsonObject();
                    if (!item.has("displayname")) continue;
                    String display = item.get("displayname").getAsString()
                            .replaceAll("§.", "").trim();
                    if (display.isEmpty()) continue;

                    String internal = item.has("internalname")
                            ? item.get("internalname").getAsString() : "";

                    // Dye items: remember the display name; lore colors only as fallback
                    // (animated dyes' lore shows two adjacent frames, not the range).
                    if (internal.startsWith("DYE_")) {
                        dyeDisplayNames.put(internal, display);
                        if (item.has("lore")) {
                            StringBuilder lore = new StringBuilder();
                            for (JsonElement line : item.getAsJsonArray("lore")) {
                                lore.append(line.getAsString().replaceAll("§.", "")).append(' ');
                            }
                            List<Integer> stops = new ArrayList<>();
                            java.util.regex.Matcher hm = hex.matcher(lore);
                            while (hm.find()) stops.add(Integer.parseInt(hm.group(1), 16));
                            if (!stops.isEmpty()) {
                                int[] ramp = new int[stops.size()];
                                for (int i = 0; i < ramp.length; i++) ramp[i] = stops.get(i);
                                dyeLoreStops.put(internal, ramp);
                            }
                        }
                        continue; // dyes shouldn't clutter the helmet-skin search
                    }

                    if (!item.has("nbttag")) continue;
                    String nbt = item.get("nbttag").getAsString();
                    if (!nbt.contains("SkullOwner")) continue;
                    java.util.regex.Matcher m = texture.matcher(nbt);
                    if (!m.find()) continue;
                    if (SKINS.putIfAbsent(display, m.group(1)) == null) {
                        added++;
                        if (added % 500 == 0) {
                            progress = "Indexing NEU skins… " + added;
                        }
                    }
                } catch (Exception ignored) {
                    // one malformed item json shouldn't sink the whole import
                }
            }
        }

        // Combine: animation frames win, then exact static hex, then lore fallback.
        java.util.Set<String> dyeIds = new java.util.HashSet<>();
        dyeIds.addAll(dyeDisplayNames.keySet());
        dyeIds.addAll(dyeAnimFrames.keySet());
        dyeIds.addAll(dyeStaticColors.keySet());
        for (String id : dyeIds) {
            if (!id.startsWith("DYE_")) continue; // dyes.json also has FAIRY_* armor
            String display = dyeDisplayNames.getOrDefault(id, deriveDyeName(id));
            int[] stops;
            float period = 4f;
            if (dyeAnimFrames.containsKey(id)) {
                stops = dyeAnimFrames.get(id);
                period = stops.length / 20f; // 1 frame per tick, 20 ticks/sec
            } else if (dyeStaticColors.containsKey(id)) {
                stops = new int[] { dyeStaticColors.get(id) };
            } else if (dyeLoreStops.containsKey(id)) {
                stops = dyeLoreStops.get(id);
            } else {
                continue;
            }
            DYE_RAMPS.put(display, new HypixelDyes.DyeDef(stops, period));
        }

        System.out.println("[DyeBrush] NEU repo merged: " + added + " cosmetic skins, "
                + DYE_RAMPS.size() + " dyes (" + dyeAnimFrames.size() + " animated)");
    }

    /** "WITHER_GOGGLES_CELESTIAL" -> "Wither Goggles Celestial". */
    private static String deriveSkinName(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** "DYE_BLACK_OPAL" -> "Black Opal Dye" (fallback when the item json is missing). */
    private static String deriveDyeName(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.substring(4).toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.append("Dye").toString();
    }

    /** The API has used both a bare string and an object {"value": ...} over time. */
    private static String textureValue(JsonElement skin) {
        if (skin.isJsonPrimitive()) return skin.getAsString();
        if (skin.isJsonObject() && skin.getAsJsonObject().has("value")) {
            return skin.getAsJsonObject().get("value").getAsString();
        }
        return null;
    }
}
