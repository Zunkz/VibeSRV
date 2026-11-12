package com.fluxer.srv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Material;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Makes an inventory icon (png) for every item and block when the server starts, from Mojang's own
 * client jar: solid blocks are drawn as an isometric cube from their model file, everything else as
 * its flat texture scaled up. Icons are saved in plugins/FluxerSRV/icons/ and only made once per
 * Minecraft version. Needs a 1.13+ jar layout (set icons.minecraft-version for older servers).
 */
public final class ItemIcons {

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** Model parents that mean "a plain cube". */
    private static final Set<String> CUBE_ROOTS = new HashSet<>(Arrays.asList(
            "block/cube_all", "block/cube_mirrored_all", "block/cube_column", "block/cube_column_horizontal",
            "block/cube_column_mirrored", "block/cube_bottom_top", "block/cube_top", "block/orientable",
            "block/orientable_with_bottom", "block/cube"));

    private static final Set<String> GRASS_TINTED = new HashSet<>(Arrays.asList(
            "block/grass_block_top", "block/grass_block_side_overlay", "block/short_grass", "block/grass",
            "block/fern", "block/tall_grass_top", "block/tall_grass_bottom", "block/large_fern_top",
            "block/large_fern_bottom", "block/sugar_cane"));

    private static final int GRASS = 0x91BD59;
    private static final int FOLIAGE = 0x77AB2F;

    private final FluxerSRV plugin;
    private final File dir;
    private volatile boolean running;

    public ItemIcons(FluxerSRV plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "icons");
    }

    // ---------------------------------------------------------------- atlas storage
    // Icons are packed into a few sheets (icons/atlas_0.png, atlas_1.png, ...) with an index
    // (icons/atlas.json: id -> [sheet, column, row]) instead of one png file per item.

    private final Object atlasLock = new Object();
    private JsonObject atlasIndex;                       // guarded by atlasLock
    private int atlasCellSize;
    private int atlasPerRow;
    private int loadedSheetNo = -1;
    private BufferedImage loadedSheet;
    private final Map<String, byte[]> pngCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** The png (inventory icon) for a material name such as "diamond_pickaxe", or null if there is none. */
    public byte[] iconPng(String id) {
        if (id == null || !id.matches("[a-z0-9_]+")) {
            return null;
        }
        byte[] cached = pngCache.get(id);
        if (cached != null) {
            return cached;
        }
        synchronized (atlasLock) {
            try {
                if (atlasIndex == null) {
                    File indexFile = new File(dir, "atlas.json");
                    if (!indexFile.isFile()) {
                        return null; // not generated yet
                    }
                    JsonObject json = new JsonParser().parse(new String(Files.readAllBytes(indexFile.toPath()),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    atlasCellSize = json.get("size").getAsInt();
                    atlasPerRow = json.get("perRow").getAsInt();
                    atlasIndex = json.getAsJsonObject("icons");
                }
                if (!atlasIndex.has(id)) {
                    return null;
                }
                JsonArray at = atlasIndex.getAsJsonArray(id);
                int sheetNo = at.get(0).getAsInt();
                if (loadedSheet == null || loadedSheetNo != sheetNo) {
                    File sheetFile = new File(dir, "atlas_" + sheetNo + ".png");
                    BufferedImage read = ImageIO.read(sheetFile);
                    if (read == null) {
                        return null;
                    }
                    loadedSheet = read;
                    loadedSheetNo = sheetNo;
                }
                BufferedImage cell = loadedSheet.getSubimage(at.get(1).getAsInt() * atlasCellSize,
                        at.get(2).getAsInt() * atlasCellSize, atlasCellSize, atlasCellSize);
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                ImageIO.write(cell, "png", bytes);
                if (pngCache.size() > 400) {
                    pngCache.clear();
                }
                byte[] png = bytes.toByteArray();
                pngCache.put(id, png);
                return png;
            } catch (Exception e) {
                plugin.getLogger().warning("Could not read the icon for " + id + " from the atlas: " + e);
                return null;
            }
        }
    }

    /** True once the icon atlas has been generated (the sheets and their index exist). */
    boolean hasIcons() {
        return new File(dir, "atlas.json").isFile();
    }

    /** The icon for a material name as an image, or null if there is none. */
    BufferedImage icon(String id) {
        byte[] png = iconPng(id);
        if (png == null) {
            return null;
        }
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(png));
        } catch (IOException e) {
            return null;
        }
    }

    private final Map<String, BufferedImage> guiCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A texture saved from the client jar when the icons were made ("inventory" = the inventory screen,
     * "ascii" = the game's font sheet), or null if it is not there.
     */
    BufferedImage guiTexture(String name) {
        if (name == null || !name.matches("[a-z0-9_]+")) {
            return null;
        }
        BufferedImage cached = guiCache.get(name);
        if (cached != null) {
            return cached;
        }
        File file = new File(new File(dir, "gui"), name + ".png");
        if (!file.isFile()) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(file);
            if (image != null) {
                guiCache.put(name, image);
            }
            return image;
        } catch (IOException e) {
            return null;
        }
    }

    /** Saves the inventory, shulker box and chest screens and the font sheet from the client jar for {@link InventoryImage}. */
    private void extractGui(ZipFile zip) {
        File guiDir = new File(dir, "gui");
        if (!guiDir.isDirectory() && !guiDir.mkdirs()) {
            plugin.getLogger().warning("Could not create " + guiDir.getPath());
            return;
        }
        String[][] files = {
                {"assets/minecraft/textures/gui/container/inventory.png", "inventory.png"},
                {"assets/minecraft/textures/font/ascii.png", "ascii.png"},
                {"assets/minecraft/textures/gui/container/shulker_box.png", "shulker_box.png"},
                {"assets/minecraft/textures/gui/container/generic_54.png", "generic_54.png"}};
        for (String[] file : files) {
            ZipEntry entry = zip.getEntry(file[0]);
            if (entry == null) {
                plugin.getLogger().warning("The client jar has no " + file[0] + "; the [inv], [ender] and shulker pictures will use "
                        + (file[1].equals("ascii.png") ? "a plain font." : "a drawn panel instead of the game's texture."));
                continue;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, new File(guiDir, file[1]).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save " + file[1] + " from the client jar: " + e);
            }
        }
    }

    /** Forget everything read from the atlas files (after they were rewritten). */
    private void resetAtlasCache() {
        synchronized (atlasLock) {
            atlasIndex = null;
            loadedSheet = null;
            loadedSheetNo = -1;
            pngCache.clear();
            guiCache.clear();
        }
    }

    /** Collects icons into square sheets and writes them as atlas_N.png plus atlas.json. */
    private final class AtlasWriter {
        private final int cell;
        private final int perRow;
        private final int perSheet;
        private final JsonObject icons = new JsonObject();
        private BufferedImage sheet;
        private int sheetNo;
        private int inSheet;
        private int total;

        AtlasWriter(int cell, int sheetPixels) {
            this.cell = cell;
            this.perRow = Math.max(1, sheetPixels / cell);
            this.perSheet = perRow * perRow;
        }

        void add(String id, BufferedImage icon) throws IOException {
            if (sheet == null) {
                sheet = new BufferedImage(perRow * cell, perRow * cell, BufferedImage.TYPE_INT_ARGB);
                inSheet = 0;
            }
            int col = inSheet % perRow;
            int row = inSheet / perRow;
            int[] pixels = icon.getRGB(0, 0, cell, cell, null, 0, cell);
            sheet.setRGB(col * cell, row * cell, cell, cell, pixels, 0, cell);
            JsonArray at = new JsonArray();
            at.add(new com.google.gson.JsonPrimitive(sheetNo));
            at.add(new com.google.gson.JsonPrimitive(col));
            at.add(new com.google.gson.JsonPrimitive(row));
            icons.add(id, at);
            inSheet++;
            total++;
            if (inSheet >= perSheet) {
                flushSheet();
            }
        }

        private void flushSheet() throws IOException {
            if (sheet == null) {
                return;
            }
            ImageIO.write(sheet, "png", new File(dir, "atlas_" + sheetNo + ".png"));
            sheet = null;
            sheetNo++;
        }

        int finish() throws IOException {
            flushSheet();
            JsonObject index = new JsonObject();
            index.addProperty("size", cell);
            index.addProperty("perRow", perRow);
            index.add("icons", icons);
            Files.write(new File(dir, "atlas.json").toPath(), index.toString().getBytes(StandardCharsets.UTF_8));
            return sheetNo;
        }

        int count() {
            return total;
        }

        boolean has(String id) {
            return icons.has(id);
        }
    }

    /** Starts generating in the background (the server keeps starting; icons appear as they are made). */
    public void generateAsync() {
        if (!plugin.getConfig().getBoolean("icons.enabled", true) || running) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    generate();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Could not generate item icons: " + t);
                } finally {
                    running = false;
                }
            }
        });
    }

    // ---------------------------------------------------------------- generation

    private void generate() throws Exception {
        running = true;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create " + dir.getPath());
            return;
        }
        String version = plugin.getConfig().getString("icons.minecraft-version", "");
        if (version == null || version.trim().isEmpty()) {
            version = detectVersion();
        }
        version = version.trim();
        int size = Math.max(32, Math.min(512, plugin.getConfig().getInt("icons.size", 128)));
        String stamp = version + "@" + size + "#r8"; // r8: shulker box + chest screens saved for the [ender] / shulker pictures, r7: inventory screen + font saved for the [inv] picture, r3: icons stored in atlas sheets, r4: chests, shields, banners, beds... drawn from entity textures, r5: chest and bed layouts fixed, r6: 26.x beds (composite head+foot models)

        File stampFile = new File(dir, ".version");
        if (stampFile.isFile() && stamp.equals(new String(Files.readAllBytes(stampFile.toPath()),
                StandardCharsets.UTF_8).trim())) {
            plugin.getLogger().info("Item icons for " + version + " are already generated.");
            return;
        }

        plugin.getLogger().info("Generating item icons for Minecraft " + version + " (first start on this version; "
                + "downloading the client jar from Mojang)...");
        File jar = new File(dir, ".client.jar");
        try {
            String jarUrl = findClientJarUrl(version);
            if (jarUrl == null) {
                // dev builds report things like "26.3.build.237": retry with just the numeric part ("26.3")
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\d+(\\.\\d+)*").matcher(version);
                if (m.find() && !m.group().equals(version)) {
                    version = m.group();
                    plugin.getLogger().info("Retrying with Minecraft version " + version + "...");
                    jarUrl = findClientJarUrl(version);
                }
            }
            if (jarUrl == null) {
                // still unknown (e.g. a snapshot Mojang no longer lists): the newest release has the same item art
                String latest = latestRelease();
                if (latest != null) {
                    plugin.getLogger().info("Minecraft " + version + " is not in Mojang's list; using the textures of "
                            + latest + " instead.");
                    version = latest;
                    jarUrl = findClientJarUrl(version);
                }
            }
            if (jarUrl == null) {
                plugin.getLogger().warning("Minecraft version " + version + " was not found in Mojang's version list. "
                        + "Set icons.minecraft-version in config.yml.");
                return;
            }
            plugin.getLogger().info("Downloading client jar: " + jarUrl);
            download(jarUrl, jar);
            plugin.getLogger().info("Client jar downloaded (" + (jar.length() / 1024 / 1024) + " MB). Drawing icons...");

            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (f.getName().endsWith(".png") || f.getName().equals("atlas.json")) {
                        f.delete();
                    }
                }
            }

            resetAtlasCache();
            int atlasSheetPixels = Math.max(512, Math.min(4096, plugin.getConfig().getInt("icons.atlas-size", 2048)));
            AtlasWriter atlas = new AtlasWriter(size, atlasSheetPixels);
            int cubes = 0;
            int models = 0;
            int entities = 0;
            int flat = 0;
            try (ZipFile zip = new ZipFile(jar)) {
                if (zip.getEntry("assets/minecraft/models/block/cube_all.json") == null) {
                    plugin.getLogger().warning("This client jar has the pre-1.13 layout; item icons need 1.13 or newer. "
                            + "Set icons.minecraft-version to a newer version to use its textures.");
                    return;
                }
                extractGui(zip);
                Assets assets = new Assets(zip);
                ModelRenderer renderer = new ModelRenderer(assets);
                int done = 0;
                int failed = 0;
                Material[] all = Material.values();
                // only materials that exist as an item stack can be shared with [item]: no wall banners, water, fire,
                // potted plants, air... (Material#isItem is 1.13+, so look it up reflectively)
                java.lang.reflect.Method isItem = null;
                try {
                    isItem = Material.class.getMethod("isItem");
                } catch (Throwable ignored) {
                    // older API: no filter beyond air
                }
                EntityIcons.MISSING.clear();
                for (Material material : all) {
                    if (++done % 250 == 0) {
                        plugin.getLogger().info("Item icons: " + done + "/" + all.length + " checked ("
                                + (cubes + models + entities + flat) + " made)...");
                    }
                    String id = material.name().toLowerCase(Locale.ROOT);
                    if (id.startsWith("legacy_") || id.equals("air") || id.equals("cave_air") || id.equals("void_air")) {
                        continue;
                    }
                    if (isItem != null) {
                        try {
                            if (!Boolean.TRUE.equals(isItem.invoke(material))) {
                                continue;
                            }
                        } catch (Throwable ignored) {
                            // keep the material if the check fails
                        }
                    }
                    try {
                        BufferedImage image = renderCube(assets, id, size);
                        int kind = 0; // 0 cube, 1 model, 2 flat, 3 entity model (chest, shield, bed...)
                        if (image == null) {
                            try {
                                image = EntityIcons.render(renderer, assets, id, size);
                                kind = 3;
                            } catch (Throwable entityError) {
                                plugin.getLogger().fine("Entity render failed for " + id + ": " + entityError);
                                image = null;
                            }
                        }
                        if (image == null) {
                            try {
                                image = renderer.render(id, size); // stairs, fences, lanterns, flat items, ...
                                kind = 1;
                            } catch (Throwable modelError) {
                                plugin.getLogger().fine("Model render failed for " + id + ": " + modelError);
                                image = null;
                            }
                        }
                        if (image == null) {
                            image = renderFlat(assets, id, size);
                            kind = 2;
                        }
                        if (image != null) {
                            atlas.add(id, image);
                            if (kind == 0) {
                                cubes++;
                            } else if (kind == 1) {
                                models++;
                            } else if (kind == 3) {
                                entities++;
                            } else {
                                flat++;
                            }
                        }
                    } catch (Throwable e) {
                        failed++;
                        plugin.getLogger().fine("No icon for " + id + ": " + e);
                    }
                }
            }
            if (cubes + models + entities + flat == 0) {
                plugin.getLogger().warning("No item icons could be drawn from the client jar for " + version
                        + " (unsupported jar layout?). Use icons.thumbnail-url in config.yml instead.");
                return;
            }
            if (!EntityIcons.MISSING.isEmpty()) {
                plugin.getLogger().warning("Entity textures not found in the client jar for " + version + ": "
                        + EntityIcons.MISSING + " (their items fall back to a plain model or no icon).");
            }
            for (String key : new String[]{"chest", "ender_chest", "shield", "white_banner", "white_bed", "shulker_box"}) {
                if (!atlas.has(key)) {
                    plugin.getLogger().warning("No icon could be drawn for " + key + " (the entity texture was not found in the "
                            + "client jar for " + version + "). Its [item] embed will have no thumbnail.");
                }
            }
            int sheets = atlas.finish();
            resetAtlasCache();
            Files.write(stampFile.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("Item icons ready (" + atlas.count() + " icons packed into " + sheets + " atlas sheets): " + cubes + " block cubes, " + models + " 3D/layered models, " + entities + " entity models (chests, shields, beds...) and " + flat + " flat items.");
        } finally {
            if (jar.exists() && !jar.delete()) {
                jar.deleteOnExit();
            }
        }
    }

    /** This server's Minecraft release, e.g. "1.21.4" or "26.1" (not a Paper build string). */
    private String detectVersion() {
        try {
            Object v = plugin.getServer().getClass().getMethod("getMinecraftVersion").invoke(plugin.getServer());
            if (v != null && !v.toString().trim().isEmpty()) {
                return v.toString().trim();
            }
        } catch (Throwable ignored) {
            // older servers: fall through
        }
        String bukkit = plugin.getServer().getBukkitVersion().split("-")[0];
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\d+(\\.\\d+)*").matcher(bukkit);
        return m.find() ? m.group() : bukkit;
    }

    private String latestRelease() {
        try {
            JsonObject latest = getJson(MANIFEST_URL).getAsJsonObject("latest");
            return latest.get("release").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private String findClientJarUrl(String version) throws IOException {
        JsonObject manifest = getJson(MANIFEST_URL);
        JsonArray versions = manifest.getAsJsonArray("versions");
        for (JsonElement element : versions) {
            JsonObject entry = element.getAsJsonObject();
            if (version.equals(entry.get("id").getAsString())) {
                JsonObject versionJson = getJson(entry.get("url").getAsString());
                return versionJson.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
            }
        }
        return null;
    }

    private JsonObject getJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", "FluxerSRV").build();
        try (Response response = plugin.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            return new JsonParser().parse(response.body().string()).getAsJsonObject();
        }
    }

    private void download(String url, File target) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", "FluxerSRV").build();
        okhttp3.OkHttpClient client = plugin.getHttpClient().newBuilder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            try (InputStream in = response.body().byteStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        }
    }

    // ---------------------------------------------------------------- jar access

    private static final class Assets implements ModelRenderer.Source {
        private final ZipFile zip;
        private final Map<String, BufferedImage> textures = new HashMap<>();
        private final Map<String, JsonObject> models = new HashMap<>();
        private final Set<String> missing = new HashSet<>();

        Assets(ZipFile zip) {
            this.zip = zip;
        }

        /** "block/stone" -> the png, with an animation strip cut down to its first frame. Null if absent. */
        @Override
        public BufferedImage texture(String path) {
            if (path == null) {
                return null;
            }
            String key = "t:" + path;
            if (textures.containsKey(path)) {
                return textures.get(path);
            }
            if (missing.contains(key)) {
                return null;
            }
            BufferedImage image = null;
            ZipEntry entry = zip.getEntry("assets/minecraft/textures/" + path + ".png");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    image = ImageIO.read(in);
                } catch (IOException ignored) {
                    image = null;
                }
            }
            if (image == null) {
                missing.add(key);
                return null;
            }
            if (image.getHeight() > image.getWidth() && image.getHeight() % image.getWidth() == 0) {
                image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
            }
            BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(image, 0, 0, null);
            textures.put(path, argb);
            return argb;
        }

        /** "block/stone" -> parsed model json, or null. */
        @Override
        public JsonObject model(String path) {
            if (models.containsKey(path)) {
                return models.get(path);
            }
            JsonObject json = null;
            ZipEntry entry = zip.getEntry("assets/minecraft/models/" + path + ".json");
            if (entry != null) {
                try (InputStreamReader in = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    json = new JsonParser().parse(in).getAsJsonObject();
                } catch (Exception ignored) {
                    json = null;
                }
            }
            models.put(path, json);
            return json;
        }

        @Override
        public JsonObject itemDefinition(String id) {
            String path = "items/" + id + ".json";
            if (models.containsKey(path)) {
                return models.get(path);
            }
            JsonObject json = null;
            ZipEntry entry = zip.getEntry("assets/minecraft/" + path);
            if (entry != null) {
                try (InputStreamReader in = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    json = new JsonParser().parse(in).getAsJsonObject();
                } catch (Exception ignored) {
                    json = null;
                }
            }
            models.put(path, json);
            return json;
        }

        @Override
        public int tint(String texturePath) {
            return tintFor(texturePath);
        }

        private List<String> entityTextures; // names under textures/entity/, without folder prefix and ".png"

        /** Entity textures moved between folders over the versions, so try the exact path, then any folder. */
        @Override
        public String findTexture(String... candidates) {
            for (String c : candidates) {
                if (zip.getEntry("assets/minecraft/textures/entity/" + c + ".png") != null) {
                    return "entity/" + c;
                }
            }
            if (entityTextures == null) {
                entityTextures = new ArrayList<>();
                String prefix = "assets/minecraft/textures/entity/";
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith(prefix) && name.endsWith(".png")) {
                        entityTextures.add(name.substring(prefix.length(), name.length() - 4));
                    }
                }
            }
            for (String c : candidates) {
                String best = null;
                for (String name : entityTextures) {
                    if ((name.equals(c) || name.endsWith("/" + c)) && (best == null || name.length() < best.length())) {
                        best = name;
                    }
                }
                if (best != null) {
                    return "entity/" + best;
                }
            }
            return null;
        }
    }

    private static String stripNamespace(String value) {
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    // ---------------------------------------------------------------- flat items

    private BufferedImage renderFlat(Assets assets, String id, int size) {
        BufferedImage texture = assets.texture("item/" + id);
        if (texture == null) {
            texture = assets.texture("block/" + id);
            if (texture != null) {
                texture = tinted(texture, tintFor("block/" + id));
            }
        }
        if (texture == null) {
            return null;
        }
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                out.setRGB(x, y, texture.getRGB(x * texture.getWidth() / size, y * texture.getHeight() / size));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- isometric cubes

    private static final int UP = 0;
    private static final int NORTH = 1;
    private static final int SOUTH = 2;
    private static final int EAST = 3;
    private static final int WEST = 4;

    /** The cube version of a block, or null if it is not a plain cube (or has no model). */
    private BufferedImage renderCube(Assets assets, String id, int size) {
        // Walk up the parent chain: collect textures (children override), find the cube type, read the GUI yaw.
        String current = "block/" + id;
        String root = null;
        Double yaw = null;
        List<JsonObject> chain = new ArrayList<>();
        for (int depth = 0; depth < 12 && current != null; depth++) {
            JsonObject model = assets.model(current);
            if (model == null) {
                return null;
            }
            chain.add(model);
            if (root == null && CUBE_ROOTS.contains(current)) {
                root = current;
            }
            if (yaw == null && model.has("display") && model.getAsJsonObject("display").has("gui")) {
                JsonObject gui = model.getAsJsonObject("display").getAsJsonObject("gui");
                if (gui.has("rotation") && gui.getAsJsonArray("rotation").size() >= 2) {
                    yaw = gui.getAsJsonArray("rotation").get(1).getAsDouble();
                }
            }
            current = model.has("parent") ? stripNamespace(model.get("parent").getAsString()) : null;
        }
        if (root == null) {
            return null;
        }
        if (yaw == null) {
            yaw = root.startsWith("block/orientable") ? 135.0 : 225.0;
        }
        Map<String, String> textures = new HashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            JsonObject model = chain.get(i);
            if (model.has("textures")) {
                for (Map.Entry<String, JsonElement> e : model.getAsJsonObject("textures").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        textures.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        }

        String[] face = faceTextures(root, textures);
        if (face == null) {
            return null;
        }
        BufferedImage top = faceImage(assets, face[UP]);
        // visible side faces for this yaw: the two whose rotated normal points at the viewer
        int[] sides = visibleSides(Math.toRadians(yaw));
        BufferedImage left = faceImage(assets, face[sides[0]]);
        BufferedImage right = faceImage(assets, face[sides[1]]);
        if (top == null || left == null || right == null) {
            return null;
        }
        return drawCube(top, left, right, size);
    }

    /** Texture path for each face (UP, NORTH, SOUTH, EAST, WEST) by cube type, or null if one is missing. */
    private String[] faceTextures(String root, Map<String, String> t) {
        String[] f = new String[5];
        switch (root) {
            case "block/cube_all":
            case "block/cube_mirrored_all":
                f[UP] = f[NORTH] = f[SOUTH] = f[EAST] = f[WEST] = resolve(t, "all");
                break;
            case "block/cube_column":
            case "block/cube_column_horizontal":
            case "block/cube_column_mirrored":
                f[UP] = resolve(t, "end");
                f[NORTH] = f[SOUTH] = f[EAST] = f[WEST] = resolve(t, "side");
                break;
            case "block/cube_bottom_top":
            case "block/cube_top":
                f[UP] = resolve(t, "top");
                f[NORTH] = f[SOUTH] = f[EAST] = f[WEST] = resolve(t, "side");
                break;
            case "block/orientable":
            case "block/orientable_with_bottom":
                f[UP] = resolve(t, "top");
                f[NORTH] = resolve(t, "front");
                f[SOUTH] = f[EAST] = f[WEST] = resolve(t, "side");
                break;
            case "block/cube":
                f[UP] = resolve(t, "up");
                f[NORTH] = resolve(t, "north");
                f[SOUTH] = resolve(t, "south");
                f[EAST] = resolve(t, "east");
                f[WEST] = resolve(t, "west");
                break;
            default:
                return null;
        }
        for (String s : f) {
            if (s == null) {
                return null;
            }
        }
        return f;
    }

    /** Follows "#name" references to a real texture path like "block/stone". */
    private static String resolve(Map<String, String> textures, String key) {
        String value = textures.get(key);
        for (int i = 0; i < 8 && value != null && value.startsWith("#"); i++) {
            value = textures.get(value.substring(1));
        }
        return value == null || value.startsWith("#") ? null : stripNamespace(value);
    }

    /** {left, right}: the two side faces facing the viewer. Model space: +x east, +z south, north is -z. */
    private static int[] visibleSides(double yawRadians) {
        int[] faces = {NORTH, SOUTH, EAST, WEST};
        double[][] normals = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}}; // x, z
        int[] best = new int[2];
        double[] bestX = new double[2];
        int found = 0;
        for (int i = 0; i < 4; i++) {
            double nx = normals[i][0];
            double nz = normals[i][1];
            double x = nx * Math.cos(yawRadians) + nz * Math.sin(yawRadians);
            double z = -nx * Math.sin(yawRadians) + nz * Math.cos(yawRadians);
            if (z > 1e-6 && found < 2) {
                best[found] = faces[i];
                bestX[found] = x;
                found++;
            }
        }
        if (found < 2) {
            return new int[]{SOUTH, EAST};
        }
        return bestX[0] <= bestX[1] ? best : new int[]{best[1], best[0]};
    }

    /** A face texture with biome tint and overlay (grass side) applied. */
    private BufferedImage faceImage(Assets assets, String path) {
        BufferedImage base = assets.texture(path);
        if (base == null) {
            return null;
        }
        base = tinted(base, tintFor(path));
        BufferedImage overlay = assets.texture(path + "_overlay");
        if (overlay != null) {
            overlay = tinted(overlay, tintFor(path + "_overlay") != 0 ? tintFor(path + "_overlay") : GRASS);
            BufferedImage merged = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < merged.getHeight(); y++) {
                for (int x = 0; x < merged.getWidth(); x++) {
                    int over = overlay.getRGB(x * overlay.getWidth() / merged.getWidth(),
                            y * overlay.getHeight() / merged.getHeight());
                    merged.setRGB(x, y, (over >>> 24) > 0 ? over : base.getRGB(x, y));
                }
            }
            return merged;
        }
        return base;
    }

    /** Multiplies the texture by an RGB tint (0 = no tint). Grayscale grass/leaf textures need this. */
    private static BufferedImage tinted(BufferedImage image, int tint) {
        if (tint == 0) {
            return image;
        }
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                out.setRGB(x, y, shade(image.getRGB(x, y), tint));
            }
        }
        return out;
    }

    private static int tintFor(String path) {
        if (GRASS_TINTED.contains(path)) {
            return GRASS;
        }
        if (path.equals("block/birch_leaves")) {
            return 0x80A755;
        }
        if (path.equals("block/spruce_leaves")) {
            return 0x619961;
        }
        if (path.equals("block/oak_leaves") || path.equals("block/jungle_leaves")
                || path.equals("block/acacia_leaves") || path.equals("block/dark_oak_leaves")
                || path.equals("block/mangrove_leaves") || path.equals("block/vine")) {
            return FOLIAGE;
        }
        if (path.equals("block/lily_pad")) {
            return 0x208030;
        }
        return 0;
    }

    /** Multiplies an ARGB pixel's color by an RGB color (alpha kept). */
    private static int shade(int argb, int rgb) {
        int a = argb >>> 24;
        int r = ((argb >> 16) & 0xFF) * ((rgb >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((rgb >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (rgb & 0xFF) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int brightness(int argb, double factor) {
        int a = argb >>> 24;
        int r = (int) (((argb >> 16) & 0xFF) * factor);
        int g = (int) (((argb >> 8) & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Draws the cube the way Minecraft's inventory does (30 degree tilt): a top rhombus and two side
     * parallelograms, each pixel looked up from its face texture (nearest, so the pixel art stays crisp).
     */
    private static BufferedImage drawCube(BufferedImage top, BufferedImage left, BufferedImage right, int n) {
        final double c = 0.7071067811865476; // edge reach in x
        final double t = 0.3535533905932738; // edge reach in y (tilt)
        final double v = 0.8660254037844386; // vertical edge
        final double total = 2 * t + v; // height of the cube in units of s
        final double s = n / total * 0.96;
        double ox = n / 2.0;
        double oy = (n - total * s) / 2.0;

        BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < n; py++) {
            for (int px = 0; px < n; px++) {
                double dx = (px + 0.5) - ox;
                double dy = (py + 0.5) - oy;

                // top: P = A + a*r + b*l, r = (c, t), l = (-c, t)
                double p = dx / (c * s);
                double q = dy / (t * s);
                double a = (p + q) / 2;
                double b = (q - p) / 2;
                if (a >= 0 && a < 1 && b >= 0 && b < 1) {
                    out.setRGB(px, py, sample(top, a, b, 1.0));
                    continue;
                }
                // left face: P = A + l + a*r + b*(0, v)
                a = dx / (c * s) + 1;
                b = (dy / s - t - t * a) / v;
                if (a >= 0 && a < 1 && b >= 0 && b < 1) {
                    out.setRGB(px, py, sample(left, a, b, 0.8));
                    continue;
                }
                // right face: P = A + (r + l) + a*(-l) + b*(0, v)
                a = dx / (c * s);
                b = (dy / s - 2 * t + t * a) / v;
                if (a >= 0 && a < 1 && b >= 0 && b < 1) {
                    out.setRGB(px, py, sample(right, a, b, 0.6));
                }
            }
        }
        return out;
    }

    private static int sample(BufferedImage texture, double u, double v, double light) {
        int x = Math.min(texture.getWidth() - 1, (int) (u * texture.getWidth()));
        int y = Math.min(texture.getHeight() - 1, (int) (v * texture.getHeight()));
        return brightness(texture.getRGB(x, y), light);
    }
}
