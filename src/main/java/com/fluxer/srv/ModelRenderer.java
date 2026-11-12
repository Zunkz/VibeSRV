package com.fluxer.srv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the inventory icon of any item or block that has a Minecraft model file: stairs, slabs, fences,
 * lanterns, torches, anvils, beds' cousins... It reads the model the way the game does (parents, texture
 * variables, cuboid "elements" with rotation, the "gui" display transform, item definitions from 1.21.4+)
 * and draws it with a small software rasteriser. Flat items (builtin/generated) are layered sprites.
 * Models drawn by the game's own entity renderers (chests, banners, ...) become a cube of their particle texture.
 */
final class ModelRenderer {

    /** Where models and textures come from (the client jar). */
    interface Source {
        /** "block/oak_stairs" -> parsed model json, or null. */
        JsonObject model(String path);

        /** assets/minecraft/items/&lt;id&gt;.json (1.21.4+), or null. */
        JsonObject itemDefinition(String id);

        /** "block/stone" -> texture (first frame), or null. */
        BufferedImage texture(String path);

        /** RGB tint for a texture path (grass, leaves...), 0 for none. */
        int tint(String texturePath);

        /**
         * Finds an entity texture (chest, shield, bed...) by name, e.g. "chest/normal", trying each candidate in
         * order and tolerating the folder moves between Minecraft versions. Returns a path such as
         * "entity/chest/normal" for {@link #texture(String)}, or null.
         */
        default String findTexture(String... candidates) {
            return null;
        }
    }

    private static final String[] DIRS = {"down", "up", "north", "south", "west", "east"};
    private static final int DOWN = 0;
    private static final int UP = 1;
    private static final int NORTH = 2;
    private static final int SOUTH = 3;
    private static final int WEST = 4;
    private static final int EAST = 5;

    private final Source src;

    ModelRenderer(Source src) {
        this.src = src;
    }

    // ------------------------------------------------------------------ model data

    private static final class Face {
        double[] uv;
        String tex;
        int rotation;
        int tint = -1;
    }

    private static final class Elem {
        double[] from = {0, 0, 0};
        double[] to = {16, 16, 16};
        double[] origin = {8, 8, 8};
        String axis = "y";
        double angle;
        boolean rescale;
        boolean shade = true;
        int tintRgb; // fixed RGB multiplied onto every face (banner dye), 0 = none
        final Face[] faces = new Face[6];
    }

    private static final class Model {
        List<Elem> elems;
        final Map<String, String> textures = new HashMap<>();
        double[] rot = {0, 0, 0};
        double[] trans = {0, 0, 0};
        double[] scale = {1, 1, 1};
        boolean hasGui;
        boolean front;
        boolean generated;
        boolean entity;
    }

    private static final class Tex {
        int w;
        int h;
        int[] px;
    }

    // ------------------------------------------------------------------ public entry

    /** The icon for an item id such as "oak_stairs", or null if no model of it can be drawn. */
    BufferedImage render(String id, int size) {
        List<Entry> models = new ArrayList<>();
        JsonObject def = src.itemDefinition(id);
        if (def != null && def.has("model")) {
            collect(def.get("model"), models, 0);
        }
        if (models.isEmpty()) {
            if (src.model("item/" + id) != null) {
                models.add(new Entry("item/" + id, null));
            } else if (src.model("block/" + id) != null) {
                models.add(new Entry("block/" + id, null));
            }
        }
        boolean moved = false;
        for (Entry e : models) {
            moved |= e.offset != null;
        }
        if (moved && models.size() > 1) {
            // a composite whose parts sit at different places (the 26.x bed: head and foot half): one drawing
            BufferedImage merged = renderMerged(models, size);
            if (merged != null) {
                return merged;
            }
        }
        BufferedImage out = null;
        for (Entry entry : models) {
            BufferedImage one = renderModel(entry.path, size);
            if (one != null) {
                out = out == null ? one : over(out, one);
            }
        }
        return out;
    }

    /** One model of an item definition, with the translation (in blocks) its "transformation" moves it by. */
    private static final class Entry {
        final String path;
        final double[] offset;

        Entry(String path, double[] offset) {
            this.path = path;
            this.offset = offset;
        }
    }

    /** The translation of a model node's "transformation" (a translation vector or a row-major matrix), or null. */
    private static double[] translation(JsonObject node) {
        if (!node.has("transformation")) {
            return null;
        }
        JsonElement t = node.get("transformation");
        try {
            if (t.isJsonObject() && t.getAsJsonObject().has("translation")) {
                double[] v = vec(t.getAsJsonObject(), "translation", null);
                return v;
            }
            if (t.isJsonArray() && t.getAsJsonArray().size() == 16) {
                JsonArray m = t.getAsJsonArray();
                return new double[]{m.get(3).getAsDouble(), m.get(7).getAsDouble(), m.get(11).getAsDouble()};
            }
        } catch (RuntimeException ignored) {
            // fall through: no translation
        }
        return null;
    }

    /**
     * Draws several models of one item definition as a single scene, each shifted by its own translation, and fits
     * the whole thing into the icon (a bed is two blocks long, more than a gui transform made for one block holds).
     */
    private BufferedImage renderMerged(List<Entry> entries, int size) {
        Model merged = new Model();
        merged.elems = new ArrayList<>();
        boolean haveGui = false;
        for (Entry entry : entries) {
            Model m = resolve(entry.path);
            if (m == null || m.elems == null) {
                continue;
            }
            double[] o = entry.offset == null ? new double[]{0, 0, 0} : entry.offset;
            for (Elem e : m.elems) {
                for (int i = 0; i < 3; i++) {
                    e.from[i] += o[i] * 16.0;
                    e.to[i] += o[i] * 16.0;
                    e.origin[i] += o[i] * 16.0;
                }
                for (Face f : e.faces) {
                    if (f != null && f.tex != null && f.tex.startsWith("#")) {
                        f.tex = resolveTex(m.textures, f.tex.substring(1)); // textures differ between the parts
                    }
                }
                merged.elems.add(e);
            }
            if (!haveGui && m.hasGui) {
                merged.rot = m.rot;
                merged.front = m.front;
                haveGui = true;
            }
        }
        if (merged.elems.isEmpty()) {
            return null;
        }
        if (!haveGui) {
            merged.rot = new double[]{30, 225, 0};
        }
        merged.hasGui = true;
        // fit: project every corner with the gui rotation, scale and centre the result into the icon
        double rx = Math.toRadians(merged.rot[0]);
        double ry = Math.toRadians(merged.rot[1]);
        double rz = Math.toRadians(merged.rot[2]);
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Elem e : merged.elems) {
            for (int c = 0; c < 8; c++) {
                double[] q = {((c & 1) == 0 ? e.from[0] : e.to[0]) / 16.0 - 0.5,
                        ((c & 2) == 0 ? e.from[1] : e.to[1]) / 16.0 - 0.5,
                        ((c & 4) == 0 ? e.from[2] : e.to[2]) / 16.0 - 0.5};
                rotate(q, rx, ry, rz);
                minX = Math.min(minX, q[0]);
                maxX = Math.max(maxX, q[0]);
                minY = Math.min(minY, q[1]);
                maxY = Math.max(maxY, q[1]);
            }
        }
        double extent = Math.max(maxX - minX, maxY - minY);
        if (extent <= 1e-9) {
            return null;
        }
        double s = 0.94 / extent;
        merged.scale = new double[]{s, s, s};
        merged.trans = new double[]{-(minX + maxX) / 2 * s * 16.0, -(minY + maxY) / 2 * s * 16.0, 0};
        return raster(merged, size);
    }

    /** Picks the model(s) an item definition (1.21.4+) shows by default. */
    private void collect(JsonElement node, List<Entry> out, int depth) {
        if (node == null || !node.isJsonObject() || depth > 8) {
            return;
        }
        JsonObject o = node.getAsJsonObject();
        String type = o.has("type") ? strip(o.get("type").getAsString()) : "model";
        if (type.equals("empty")) {
            return;
        }
        if (type.equals("model")) {
            if (o.has("model") && o.get("model").isJsonPrimitive()) {
                out.add(new Entry(strip(o.get("model").getAsString()), translation(o)));
            }
            return;
        }
        if (type.equals("composite")) {
            if (o.has("models") && o.get("models").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("models")) {
                    collect(e, out, depth + 1);
                }
            }
            return;
        }
        if (type.equals("special")) {
            if (o.has("base") && o.get("base").isJsonPrimitive()) {
                out.add(new Entry(strip(o.get("base").getAsString()), null));
            }
            return;
        }
        // condition / select / range_dispatch / others: the default branch
        for (String key : new String[]{"fallback", "on_false", "on_true"}) {
            if (o.has(key) && o.get(key).isJsonObject()) {
                collect(o.get(key), out, depth + 1);
                return;
            }
        }
        for (String key : new String[]{"cases", "entries"}) {
            if (o.has(key) && o.get(key).isJsonArray() && o.getAsJsonArray(key).size() > 0) {
                JsonElement first = o.getAsJsonArray(key).get(0);
                if (first.isJsonObject() && first.getAsJsonObject().has("model")) {
                    collect(first.getAsJsonObject().get("model"), out, depth + 1);
                    return;
                }
            }
        }
        if (o.has("model")) {
            if (o.get("model").isJsonObject()) {
                collect(o.get("model"), out, depth + 1);
            } else if (o.get("model").isJsonPrimitive()) {
                out.add(new Entry(strip(o.get("model").getAsString()), translation(o)));
            }
        }
    }

    // ------------------------------------------------------------------ model resolving

    private Model resolve(String path) {
        Model m = new Model();
        List<JsonObject> chain = new ArrayList<>();
        String current = path;
        for (int i = 0; i < 16 && current != null; i++) {
            if (current.equals("builtin/generated")) {
                m.generated = true;
                break;
            }
            if (current.equals("builtin/entity")) {
                m.entity = true;
                break;
            }
            if (current.startsWith("builtin/")) {
                break;
            }
            JsonObject json = src.model(current);
            if (json == null) {
                break;
            }
            chain.add(json);
            current = json.has("parent") && json.get("parent").isJsonPrimitive()
                    ? strip(json.get("parent").getAsString()) : null;
        }
        if (chain.isEmpty() && !m.generated && !m.entity) {
            return null;
        }
        // textures: the root first, children override
        for (int i = chain.size() - 1; i >= 0; i--) {
            JsonObject json = chain.get(i);
            if (json.has("textures") && json.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("textures").entrySet()) {
                    String value = textureValue(e.getValue());
                    if (value != null) {
                        m.textures.put(e.getKey(), value);
                    }
                }
            }
        }
        // elements, gui transform and light: the closest model that has them
        boolean gotElements = false;
        boolean gotLight = false;
        for (JsonObject json : chain) {
            if (!gotElements && json.has("elements") && json.get("elements").isJsonArray()) {
                m.elems = parseElements(json.getAsJsonArray("elements"));
                gotElements = true;
            }
            if (!m.hasGui && json.has("display") && json.get("display").isJsonObject()) {
                JsonObject display = json.getAsJsonObject("display");
                if (display.has("gui") && display.get("gui").isJsonObject()) {
                    JsonObject gui = display.getAsJsonObject("gui");
                    m.rot = vec(gui, "rotation", m.rot);
                    m.trans = vec(gui, "translation", m.trans);
                    m.scale = vec(gui, "scale", m.scale);
                    m.hasGui = true;
                }
            }
            if (!gotLight && json.has("gui_light") && json.get("gui_light").isJsonPrimitive()) {
                m.front = json.get("gui_light").getAsString().equals("front");
                gotLight = true;
            }
        }
        return m;
    }

    private static String textureValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        if (value.isJsonObject() && value.getAsJsonObject().has("sprite")
                && value.getAsJsonObject().get("sprite").isJsonPrimitive()) {
            return value.getAsJsonObject().get("sprite").getAsString();
        }
        return null;
    }

    private static List<Elem> parseElements(JsonArray array) {
        List<Elem> list = new ArrayList<>();
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            Elem e = new Elem();
            e.from = vec(o, "from", e.from);
            e.to = vec(o, "to", e.to);
            if (o.has("rotation") && o.get("rotation").isJsonObject()) {
                JsonObject r = o.getAsJsonObject("rotation");
                e.origin = vec(r, "origin", e.origin);
                if (r.has("axis") && r.get("axis").isJsonPrimitive()) {
                    e.axis = r.get("axis").getAsString();
                }
                if (r.has("angle") && r.get("angle").isJsonPrimitive()) {
                    e.angle = r.get("angle").getAsDouble();
                }
                e.rescale = r.has("rescale") && r.get("rescale").isJsonPrimitive()
                        && r.get("rescale").getAsBoolean();
            }
            if (o.has("shade") && o.get("shade").isJsonPrimitive()) {
                e.shade = o.get("shade").getAsBoolean();
            }
            if (o.has("faces") && o.get("faces").isJsonObject()) {
                JsonObject faces = o.getAsJsonObject("faces");
                for (int d = 0; d < 6; d++) {
                    if (!faces.has(DIRS[d]) || !faces.get(DIRS[d]).isJsonObject()) {
                        continue;
                    }
                    JsonObject fo = faces.getAsJsonObject(DIRS[d]);
                    Face f = new Face();
                    if (fo.has("texture") && fo.get("texture").isJsonPrimitive()) {
                        f.tex = fo.get("texture").getAsString();
                    }
                    if (fo.has("uv") && fo.get("uv").isJsonArray() && fo.getAsJsonArray("uv").size() >= 4) {
                        JsonArray uv = fo.getAsJsonArray("uv");
                        f.uv = new double[]{uv.get(0).getAsDouble(), uv.get(1).getAsDouble(),
                                uv.get(2).getAsDouble(), uv.get(3).getAsDouble()};
                    }
                    if (fo.has("rotation") && fo.get("rotation").isJsonPrimitive()) {
                        f.rotation = fo.get("rotation").getAsInt();
                    }
                    if (fo.has("tintindex") && fo.get("tintindex").isJsonPrimitive()) {
                        f.tint = fo.get("tintindex").getAsInt();
                    }
                    e.faces[d] = f;
                }
            }
            list.add(e);
        }
        return list;
    }

    private static double[] vec(JsonObject o, String key, double[] fallback) {
        if (o.has(key) && o.get(key).isJsonArray() && o.getAsJsonArray(key).size() >= 3) {
            JsonArray a = o.getAsJsonArray(key);
            try {
                return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String strip(String value) {
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    /** Follows "#name" references to a real texture path, or null. */
    private static String resolveTex(Map<String, String> textures, String key) {
        String value = textures.get(key);
        for (int i = 0; i < 10 && value != null && value.startsWith("#"); i++) {
            value = textures.get(value.substring(1));
        }
        return value == null || value.startsWith("#") ? null : strip(value);
    }

    // ------------------------------------------------------------------ hand-built entity models

    /**
     * One cuboid of an entity model (chest, shield, bed, head...), in block space: 16 units per block, y up.
     * Entity models are not in any model json (the game builds them in code), so they are described here and
     * textured from the entity texture sheet with the game's cuboid layout.
     */
    static final class Part {
        final double[] from;
        final double[] to;
        final int[][] rect = new int[6][];   // per face (DOWN, UP, NORTH, SOUTH, WEST, EAST): u1, v1, u2, v2 in texture pixels
        final int[] rotation = new int[6];
        int tint;

        Part(double[] from, double[] to) {
            this.from = from;
            this.to = to;
        }

        Part tint(int rgb) {
            this.tint = rgb;
            return this;
        }

        /** Mirrors one face vertically (swaps its top and bottom texture edges). */
        Part flipV(int dir) {
            if (rect[dir] != null) {
                int t = rect[dir][1];
                rect[dir][1] = rect[dir][3];
                rect[dir][3] = t;
            }
            return this;
        }

        /**
         * A cuboid of a block-entity model that the game draws in block space as it is written (no flips): chests,
         * the chest latch, bed legs. Every cuboid is textured with the ModelPart net, whose rows are laid out for a
         * y-down entity, so in the y-up block the art is turned upside down: the four sides are rotated 180 degrees
         * (the chest's front, with the notch under the latch, is the "south" polygon, which faces +z), the top polygon
         * is the net's "down" one with its v axis running north, the underside is the net's "up" one.
         */
        static Part blockBox(double[] from, double[] to, int dx, int dy, int dz, int u, int v) {
            Part p = new Part(from, to);
            int[] w = {u, v + dz, u + dz, v + dz + dy};
            int[] n = {u + dz, v + dz, u + dz + dx, v + dz + dy};
            int[] e = {u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy};
            int[] s = {u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy};
            int[] up = {u + dz, v, u + dz + dx, v + dz};
            int[] down = {u + dz + dx, v, u + dz + 2 * dx, v + dz};
            p.rect[DOWN] = up;
            p.rect[UP] = down;
            p.rect[NORTH] = n;
            p.rect[SOUTH] = s;
            p.rect[WEST] = w;
            p.rect[EAST] = e;
            p.flipV(UP);
            for (int d = NORTH; d <= EAST; d++) {
                p.rotation[d] = 180;
            }
            return p;
        }

        Part face(int dir, int u1, int v1, int u2, int v2, int rotation) {
            this.rect[dir] = new int[]{u1, v1, u2, v2};
            this.rotation[dir] = rotation;
            return this;
        }

        /**
         * A cuboid textured like the game's ModelPart boxes: size dx*dy*dz in texture pixels, texOffs (u, v).
         * flipZ false = block entity layout (chest, shield, banner: the entity's front, "north" polygon, ends up on
         * the south side and its back on the north side); true = mob layout (heads: the front stays on the north side).
         */
        static Part box(double[] from, double[] to, int dx, int dy, int dz, int u, int v, boolean flipZ) {
            Part p = new Part(from, to);
            int[] w = {u, v + dz, u + dz, v + dz + dy};
            int[] n = {u + dz, v + dz, u + dz + dx, v + dz + dy};
            int[] e = {u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy};
            int[] s = {u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy};
            int[] up = {u + dz, v, u + dz + dx, v + dz};
            int[] down = {u + dz + dx, v, u + dz + 2 * dx, v + dz};
            if (flipZ) {
                p.rect[DOWN] = down;
                p.rect[UP] = up;
                p.rect[NORTH] = n;
                p.rect[SOUTH] = s;
                p.rect[WEST] = e;
                p.rect[EAST] = w;
                p.rotation[UP] = 180;
            } else {
                // block entities (chest, shulker box...): the first polygon of the top row is the inside/underside,
                // the second one is the outside top (drawn upside down relative to it)
                p.rect[DOWN] = up;
                p.rect[UP] = down;
                p.rotation[UP] = 180;
                p.rect[NORTH] = s;
                p.rect[SOUTH] = n;
                p.rect[WEST] = w;
                p.rect[EAST] = e;
            }
            return p;
        }
    }

    /** True when no pixel of the texture rectangle (u1, v1, u2, v2) is visible. */
    private static boolean blank(BufferedImage t, int[] r) {
        int x1 = Math.min(r[0], r[2]);
        int x2 = Math.max(r[0], r[2]);
        int y1 = Math.min(r[1], r[3]);
        int y2 = Math.max(r[1], r[3]);
        for (int y = Math.max(0, y1); y < Math.min(t.getHeight(), y2); y++) {
            for (int x = Math.max(0, x1); x < Math.min(t.getWidth(), x2); x++) {
                if ((t.getRGB(x, y) >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Draws entity-model parts textured from one entity texture. {@code rot} is the gui rotation (x, y, z degrees);
     * the result is scaled and centred to fill the icon, so the parts can be written in natural coordinates.
     */
    BufferedImage renderParts(String texturePath, List<Part> parts, double[] rot, int size) {
        BufferedImage image = src.texture(texturePath);
        if (image == null || parts.isEmpty()) {
            return null;
        }
        double tw = image.getWidth();
        double th = image.getHeight();
        Model m = new Model();
        m.textures.put("e", texturePath);
        m.rot = rot;
        m.hasGui = true;
        m.elems = new ArrayList<>();
        for (Part p : parts) {
            Elem e = new Elem();
            e.from = p.from;
            e.to = p.to;
            e.tintRgb = p.tint;
            for (int d = 0; d < 6; d++) {
                int[] rect = p.rect[d];
                int rotation = p.rotation[d];
                if (rect == null) {
                    continue;
                }
                if (blank(image, rect)) {
                    // this part of the sheet has no pixels (layout differs in this version): borrow a face that has
                    rect = null;
                    for (int o : new int[]{UP, NORTH, SOUTH, EAST, WEST, DOWN}) {
                        if (p.rect[o] != null && !blank(image, p.rect[o])) {
                            rect = p.rect[o];
                            rotation = 0;
                            break;
                        }
                    }
                    if (rect == null) {
                        continue;
                    }
                }
                Face f = new Face();
                f.tex = "#e";
                f.uv = new double[]{rect[0] * 16.0 / tw, rect[1] * 16.0 / th, rect[2] * 16.0 / tw, rect[3] * 16.0 / th};
                f.rotation = rotation;
                e.faces[d] = f;
            }
            m.elems.add(e);
        }
        // fit: project every corner with the gui rotation, then scale/centre the result into the icon
        double rx = Math.toRadians(rot[0]);
        double ry = Math.toRadians(rot[1]);
        double rz = Math.toRadians(rot[2]);
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Part p : parts) {
            for (int c = 0; c < 8; c++) {
                double[] q = {((c & 1) == 0 ? p.from[0] : p.to[0]) / 16.0 - 0.5,
                        ((c & 2) == 0 ? p.from[1] : p.to[1]) / 16.0 - 0.5,
                        ((c & 4) == 0 ? p.from[2] : p.to[2]) / 16.0 - 0.5};
                rotate(q, rx, ry, rz);
                minX = Math.min(minX, q[0]);
                maxX = Math.max(maxX, q[0]);
                minY = Math.min(minY, q[1]);
                maxY = Math.max(maxY, q[1]);
            }
        }
        double extent = Math.max(maxX - minX, maxY - minY);
        if (extent <= 1e-9) {
            return null;
        }
        double s = 0.94 / extent;
        m.scale = new double[]{s, s, s};
        m.trans = new double[]{-(minX + maxX) / 2 * s * 16.0, -(minY + maxY) / 2 * s * 16.0, 0};
        return raster(m, size);
    }

    // ------------------------------------------------------------------ drawing

    private BufferedImage renderModel(String path, int size) {
        Model m = resolve(path);
        if (m == null) {
            return null;
        }
        if (m.generated) {
            return layers(m, size);
        }
        if (m.entity) {
            // drawn by the game's entity renderer (chest, banner, shield...): a cube of its particle texture
            if (resolveTex(m.textures, "particle") == null) {
                return null;
            }
            Elem cube = new Elem();
            for (int d = 0; d < 6; d++) {
                Face f = new Face();
                f.tex = "#particle";
                cube.faces[d] = f;
            }
            m.elems = new ArrayList<>();
            m.elems.add(cube);
            if (!m.hasGui) {
                m.rot = new double[]{30, 225, 0};
                m.scale = new double[]{0.625, 0.625, 0.625};
            }
        }
        if (m.elems == null || m.elems.isEmpty()) {
            return null;
        }
        return raster(m, size);
    }

    /** Flat item: its sprite layers (layer0, layer1, ...) stacked. */
    private BufferedImage layers(Model m, int size) {
        BufferedImage out = null;
        for (int i = 0; i < 5; i++) {
            String path = resolveTex(m.textures, "layer" + i);
            if (path == null) {
                continue;
            }
            BufferedImage texture = src.texture(path);
            if (texture == null) {
                continue;
            }
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    scaled.setRGB(x, y, texture.getRGB(x * texture.getWidth() / size,
                            y * texture.getHeight() / size));
                }
            }
            out = out == null ? scaled : over(out, scaled);
        }
        return out;
    }

    private BufferedImage raster(Model m, int size) {
        final int ss = size <= 192 ? 3 : 2;
        final int n = size * ss;
        int[] color = new int[n * n];
        double[] depth = new double[n * n];
        Arrays.fill(depth, -1e18);

        double rx = Math.toRadians(m.rot[0]);
        double ry = Math.toRadians(m.rot[1]);
        double rz = Math.toRadians(m.rot[2]);
        Map<String, Tex> textures = new HashMap<>();

        for (Elem e : m.elems) {
            for (int d = 0; d < 6; d++) {
                Face face = e.faces[d];
                if (face == null || face.tex == null) {
                    continue;
                }
                String path = face.tex.startsWith("#")
                        ? resolveTex(m.textures, face.tex.substring(1)) : strip(face.tex);
                if (path == null) {
                    continue;
                }
                int tint = e.tintRgb != 0 ? e.tintRgb : (face.tint >= 0 ? src.tint(path) : 0);
                Tex tex = texture(textures, path, tint);
                if (tex == null) {
                    continue;
                }

                double[][] v = faceVerts(d, e.from, e.to);
                if (e.angle != 0) {
                    elementRotate(v, e);
                }
                double[][] p = new double[4][3];
                for (int i = 0; i < 4; i++) {
                    double[] q = {
                            (v[i][0] / 16.0 - 0.5) * m.scale[0],
                            (v[i][1] / 16.0 - 0.5) * m.scale[1],
                            (v[i][2] / 16.0 - 0.5) * m.scale[2]};
                    rotate(q, rx, ry, rz);
                    p[i][0] = q[0] + m.trans[0] / 16.0;
                    p[i][1] = q[1] + m.trans[1] / 16.0;
                    p[i][2] = q[2] + m.trans[2] / 16.0;
                }

                // outward normal of the face after all transforms; only faces turned to the viewer are drawn
                double ax = p[3][0] - p[0][0];
                double ay = p[3][1] - p[0][1];
                double az = p[3][2] - p[0][2];
                double bx = p[1][0] - p[0][0];
                double by = p[1][1] - p[0][1];
                double bz = p[1][2] - p[0][2];
                double nx = ay * bz - az * by;
                double ny = az * bx - ax * bz;
                double nz = ax * by - ay * bx;
                if (nz <= 1e-9) {
                    continue;
                }
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx /= len;
                ny /= len;
                nz /= len;
                double shade = 1.0;
                if (e.shade && !m.front) {
                    double lx = -0.3;
                    double ly = 0.85;
                    double lz = 0.43;
                    double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
                    shade = 0.55 + 0.45 * Math.max(0, (nx * lx + ny * ly + nz * lz) / ll);
                }

                double[] uv = face.uv != null ? face.uv : defaultUv(d, e.from, e.to);
                double[][] corners = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
                int steps = ((face.rotation / 90) % 4 + 4) % 4;
                double[] su = new double[4];
                double[] sv = new double[4];
                double[] sx = new double[4];
                double[] sy = new double[4];
                double[] sz = new double[4];
                for (int i = 0; i < 4; i++) {
                    double[] c = corners[((i - steps) % 4 + 4) % 4];
                    su[i] = c[0];
                    sv[i] = c[1];
                    sx[i] = (p[i][0] + 0.5) * n;
                    sy[i] = (0.5 - p[i][1]) * n;
                    sz[i] = p[i][2];
                }
                triangle(color, depth, n, sx, sy, sz, su, sv, 0, 1, 2, tex, shade);
                triangle(color, depth, n, sx, sy, sz, su, sv, 0, 2, 3, tex, shade);
            }
        }

        // average the supersampled pixels down to the icon size
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int samples = ss * ss;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                for (int sy = 0; sy < ss; sy++) {
                    for (int sx = 0; sx < ss; sx++) {
                        int c = color[(y * ss + sy) * n + x * ss + sx];
                        int ca = c >>> 24;
                        if (ca == 0) {
                            continue;
                        }
                        a += ca;
                        r += (long) ((c >> 16) & 0xFF) * ca;
                        g += (long) ((c >> 8) & 0xFF) * ca;
                        b += (long) (c & 0xFF) * ca;
                    }
                }
                if (a > 0) {
                    int oa = (int) (a / samples);
                    out.setRGB(x, y, (oa << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a));
                }
            }
        }
        return out;
    }

    private Tex texture(Map<String, Tex> cache, String path, int tint) {
        String key = path + "|" + tint;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Tex tex = null;
        BufferedImage image = src.texture(path);
        if (image != null) {
            tex = new Tex();
            tex.w = image.getWidth();
            tex.h = image.getHeight();
            tex.px = image.getRGB(0, 0, tex.w, tex.h, null, 0, tex.w);
            if (tint != 0) {
                for (int i = 0; i < tex.px.length; i++) {
                    int c = tex.px[i];
                    int r = ((c >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
                    int g = ((c >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
                    int b = (c & 0xFF) * (tint & 0xFF) / 255;
                    tex.px[i] = (c & 0xFF000000) | (r << 16) | (g << 8) | b;
                }
            }
        }
        cache.put(key, tex);
        return tex;
    }

    /** Fills one triangle: depth-tested (nearer = larger z), texture looked up per pixel (nearest). */
    private static void triangle(int[] color, double[] depth, int n, double[] x, double[] y, double[] z,
                                 double[] u, double[] v, int i0, int i1, int i2, Tex tex, double shade) {
        double area = (x[i1] - x[i0]) * (y[i2] - y[i0]) - (x[i2] - x[i0]) * (y[i1] - y[i0]);
        if (Math.abs(area) < 1e-9) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(x[i0], Math.min(x[i1], x[i2]))));
        int maxX = Math.min(n - 1, (int) Math.ceil(Math.max(x[i0], Math.max(x[i1], x[i2]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y[i0], Math.min(y[i1], y[i2]))));
        int maxY = Math.min(n - 1, (int) Math.ceil(Math.max(y[i0], Math.max(y[i1], y[i2]))));
        for (int py = minY; py <= maxY; py++) {
            double cy = py + 0.5;
            for (int px = minX; px <= maxX; px++) {
                double cx = px + 0.5;
                double w0 = ((x[i1] - cx) * (y[i2] - cy) - (x[i2] - cx) * (y[i1] - cy)) / area;
                double w1 = ((x[i2] - cx) * (y[i0] - cy) - (x[i0] - cx) * (y[i2] - cy)) / area;
                double w2 = 1.0 - w0 - w1;
                if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) {
                    continue;
                }
                int index = py * n + px;
                double depthHere = w0 * z[i0] + w1 * z[i1] + w2 * z[i2];
                if (depthHere + 1e-7 < depth[index]) {
                    continue;
                }
                double tu = w0 * u[i0] + w1 * u[i1] + w2 * u[i2];
                double tv = w0 * v[i0] + w1 * v[i1] + w2 * v[i2];
                int tx = Math.max(0, Math.min(tex.w - 1, (int) Math.floor(tu / 16.0 * tex.w)));
                int ty = Math.max(0, Math.min(tex.h - 1, (int) Math.floor(tv / 16.0 * tex.h)));
                int c = tex.px[ty * tex.w + tx];
                int a = c >>> 24;
                if (a == 0) {
                    continue;
                }
                int r = (int) (((c >> 16) & 0xFF) * shade);
                int g = (int) (((c >> 8) & 0xFF) * shade);
                int b = (int) ((c & 0xFF) * shade);
                int pixel = (a << 24) | (r << 16) | (g << 8) | b;
                if (a < 255) {
                    color[index] = blend(pixel, color[index]);
                } else {
                    color[index] = pixel;
                    depth[index] = depthHere;
                }
            }
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    /** The four corners of a face; corner i uses the i-th corner of its uv rectangle. */
    private static double[][] faceVerts(int d, double[] f, double[] t) {
        double x1 = f[0];
        double y1 = f[1];
        double z1 = f[2];
        double x2 = t[0];
        double y2 = t[1];
        double z2 = t[2];
        switch (d) {
            case UP:
                return new double[][]{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}};
            case DOWN:
                return new double[][]{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}};
            case NORTH:
                return new double[][]{{x2, y2, z1}, {x1, y2, z1}, {x1, y1, z1}, {x2, y1, z1}};
            case SOUTH:
                return new double[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y1, z2}, {x1, y1, z2}};
            case WEST:
                return new double[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z2}, {x1, y1, z1}};
            default: // EAST
                return new double[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z1}, {x2, y1, z2}};
        }
    }

    /** The uv rectangle the game uses when a face has no "uv". */
    private static double[] defaultUv(int d, double[] f, double[] t) {
        double x1 = f[0];
        double y1 = f[1];
        double z1 = f[2];
        double x2 = t[0];
        double y2 = t[1];
        double z2 = t[2];
        switch (d) {
            case DOWN:
                return new double[]{x1, 16 - z2, x2, 16 - z1};
            case UP:
                return new double[]{x1, z1, x2, z2};
            case NORTH:
                return new double[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case SOUTH:
                return new double[]{x1, 16 - y2, x2, 16 - y1};
            case WEST:
                return new double[]{z1, 16 - y2, z2, 16 - y1};
            default: // EAST
                return new double[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
        }
    }

    /** An element's own "rotation" (around its origin on one axis), with the optional rescale. */
    private static void elementRotate(double[][] verts, Elem e) {
        double angle = Math.toRadians(e.angle);
        double scale = 1.0;
        if (e.rescale) {
            double cos = Math.cos(angle);
            if (Math.abs(cos) > 1e-6) {
                scale = 1.0 / Math.abs(cos);
            }
        }
        for (double[] v : verts) {
            double[] q = {v[0] - e.origin[0], v[1] - e.origin[1], v[2] - e.origin[2]};
            if (e.axis.equals("x")) {
                rotate(q, angle, 0, 0);
                q[1] *= scale;
                q[2] *= scale;
            } else if (e.axis.equals("z")) {
                rotate(q, 0, 0, angle);
                q[0] *= scale;
                q[1] *= scale;
            } else {
                rotate(q, 0, angle, 0);
                q[0] *= scale;
                q[2] *= scale;
            }
            v[0] = q[0] + e.origin[0];
            v[1] = q[1] + e.origin[1];
            v[2] = q[2] + e.origin[2];
        }
    }

    /** The game's rotationXYZ: around z first, then y, then x (radians). */
    private static void rotate(double[] p, double rx, double ry, double rz) {
        double x = p[0];
        double y = p[1];
        double z = p[2];
        if (rz != 0) {
            double c = Math.cos(rz);
            double s = Math.sin(rz);
            double nx = x * c - y * s;
            y = x * s + y * c;
            x = nx;
        }
        if (ry != 0) {
            double c = Math.cos(ry);
            double s = Math.sin(ry);
            double nx = x * c + z * s;
            z = -x * s + z * c;
            x = nx;
        }
        if (rx != 0) {
            double c = Math.cos(rx);
            double s = Math.sin(rx);
            double ny = y * c - z * s;
            z = y * s + z * c;
            y = ny;
        }
        p[0] = x;
        p[1] = y;
        p[2] = z;
    }

    // ------------------------------------------------------------------ compositing

    private static BufferedImage over(BufferedImage below, BufferedImage above) {
        int w = below.getWidth();
        int h = below.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, blend(above.getRGB(x, y), below.getRGB(x, y)));
            }
        }
        return out;
    }

    /** Source-over blend of ARGB pixel s on top of d. */
    private static int blend(int s, int d) {
        int sa = s >>> 24;
        if (sa == 255) {
            return s;
        }
        if (sa == 0) {
            return d;
        }
        int da = d >>> 24;
        if (da == 0) {
            return s;
        }
        double sf = sa / 255.0;
        double df = da / 255.0 * (1.0 - sf);
        double oa = sf + df;
        int r = (int) ((((s >> 16) & 0xFF) * sf + ((d >> 16) & 0xFF) * df) / oa);
        int g = (int) ((((s >> 8) & 0xFF) * sf + ((d >> 8) & 0xFF) * df) / oa);
        int b = (int) (((s & 0xFF) * sf + (d & 0xFF) * df) / oa);
        return ((int) (oa * 255 + 0.5) << 24) | (r << 16) | (g << 8) | b;
    }
}
