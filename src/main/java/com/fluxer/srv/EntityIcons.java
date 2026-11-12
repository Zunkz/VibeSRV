package com.fluxer.srv;

import com.fluxer.srv.ModelRenderer.Part;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Inventory icons for the items the game draws with entity renderers instead of model files: chests, shulker
 * boxes, shields, banners, beds and mob heads. Their shape is built in code by the game, so it is described here
 * as a few cuboids (the same boxes and texture offsets as the game's models) and textured from the entity
 * texture sheets in the client jar.
 */
final class EntityIcons {

    private EntityIcons() {
    }

    /** Entity textures that could not be found in the client jar (for the startup log). */
    static final java.util.Set<String> MISSING = java.util.Collections.synchronizedSet(new java.util.TreeSet<String>());

    private static String find(ModelRenderer.Source src, String... candidates) {
        String path = src.findTexture(candidates);
        if (path == null) {
            MISSING.add(candidates[0]);
        }
        return path;
    }

    private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    /** DyeColor#getTextureDiffuseColor, the colour banners are tinted with. */
    private static final int[] DYE_RGB = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};

    private static final double[] BLOCK_VIEW = {30, 225, 0};
    /** The gui rotations of the game's item models (item/template_chest and item/template_bed). */
    private static final double[] CHEST_VIEW = {30, 45, 0};
    private static final double[] BED_VIEW = {30, 160, 0};

    /** The icon for an item id such as "chest" or "red_banner", or null if this is not an entity-drawn item. */
    static BufferedImage render(ModelRenderer renderer, ModelRenderer.Source src, String id, int size) {
        try {
            if (id.equals("chest") || id.equals("trapped_chest") || id.equals("ender_chest")
                    || id.endsWith("copper_chest")) {
                return chest(renderer, src, id, size);
            }
            if (id.equals("shulker_box") || id.endsWith("_shulker_box")) {
                return shulkerBox(renderer, src, id, size);
            }
            if (id.equals("shield")) {
                return shield(renderer, src, size);
            }
            if (id.endsWith("_banner")) {
                return banner(renderer, src, id, size);
            }
            if (id.endsWith("_bed")) {
                return bed(renderer, src, id, size);
            }
            if (id.endsWith("_head") || id.endsWith("_skull")) {
                return head(renderer, src, id, size);
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** "red" for "red_banner" when id is one of the sixteen dye prefixed ids, else -1. */
    private static int dyeIndex(String id, String suffix) {
        if (!id.endsWith(suffix)) {
            return -1;
        }
        String color = id.substring(0, id.length() - suffix.length());
        return Arrays.asList(DYES).indexOf(color);
    }

    private static double[] v(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    // ---------------------------------------------------------------- chests

    private static BufferedImage chest(ModelRenderer r, ModelRenderer.Source src, String id, int size) {
        String name;
        String base = id.startsWith("waxed_") ? id.substring("waxed_".length()) : id;
        if (base.equals("trapped_chest")) {
            name = "trapped";
        } else if (base.equals("ender_chest")) {
            name = "ender";
        } else if (base.equals("copper_chest")) {
            name = "copper";
        } else if (base.endsWith("_copper_chest")) {
            name = "copper_" + base.substring(0, base.length() - "_copper_chest".length());
        } else {
            name = "normal";
        }
        String path = find(src, "chest/" + name);
        if (path == null) {
            return null;
        }
        BufferedImage tex = src.texture(path);
        if (tex == null) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        // The game's ChestModel (block space, front = +z / south): body, lid and latch, same boxes and texture offsets.
        // The item is shown with the model's own gui rotation (item/template_chest: 30, 45, 0), front on the right.
        parts.add(Part.blockBox(v(1, 0, 1), v(15, 10, 15), 14, 10, 14, 0, 19));   // body
        parts.add(Part.blockBox(v(1, 9, 1), v(15, 14, 15), 14, 5, 14, 0, 0));     // lid
        parts.add(Part.blockBox(v(7, 7, 15), v(9, 11, 16), 2, 4, 1, 0, 0));       // latch, on the front
        return r.renderParts(path, parts, CHEST_VIEW, size);
    }

    // ---------------------------------------------------------------- shulker boxes

    private static BufferedImage shulkerBox(ModelRenderer r, ModelRenderer.Source src, String id, int size) {
        String path = null;
        if (!id.equals("shulker_box")) {
            String color = id.substring(0, id.length() - "_shulker_box".length());
            path = find(src, "shulker/shulker_" + color, "shulker_" + color);
        }
        if (path == null) {
            path = find(src, "shulker/shulker", "shulker");
        }
        if (path == null) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        parts.add(shulkerPart(v(0, 0, 0), v(16, 8, 16), 16, 8, 16, 0, 28));     // base
        parts.add(shulkerPart(v(0, 4, 0), v(16, 16, 16), 16, 12, 16, 0, 0));    // lid (drawn over the base)
        return r.renderParts(path, parts, BLOCK_VIEW, size);
    }

    /**
     * A cuboid of the shulker model. Unlike the chest, the game draws the shulker box with scale(1, -1, -1), which
     * turns the y-down model upright: the net's first top-row tile (entity min y) becomes the outside top, as
     * for mobs, and its second tile (v reversed) the underside. The sides need no rotation, and the front (the
     * net's "north" side) ends up on the south side, which {@link Part#box} already does for block entities.
     */
    private static Part shulkerPart(double[] from, double[] to, int dx, int dy, int dz, int u, int v) {
        Part p = Part.box(from, to, dx, dy, dz, u, v, false);
        p.face(1, u + dz, v, u + dz + dx, v + dz, 0);                  // UP: the first tile, as it is on the sheet
        p.face(0, u + dz + dx, v + dz, u + dz + 2 * dx, v, 0);          // DOWN: the second tile, v reversed
        return p;
    }

    // ---------------------------------------------------------------- shield

    private static BufferedImage shield(ModelRenderer r, ModelRenderer.Source src, int size) {
        String path = find(src, "shield_base_nopattern", "shield/base_nopattern", "shield/shield_base_nopattern",
                "shield_base", "shield/base", "shield");
        if (path == null) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        parts.add(Part.box(v(7, 5, 9), v(9, 11, 15), 2, 6, 6, 26, 0, false));      // handle, behind the plate
        parts.add(Part.box(v(2, -3, 15), v(14, 19, 16), 12, 22, 1, 0, 0, false));  // plate
        return r.renderParts(path, parts, new double[]{15, -25, 0}, size);
    }

    // ---------------------------------------------------------------- banners

    private static BufferedImage banner(ModelRenderer r, ModelRenderer.Source src, String id, int size) {
        int dye = dyeIndex(id, "_banner");
        if (dye < 0) {
            return null; // ominous banner and friends: patterns are not drawn here
        }
        String path = find(src, "banner_base", "banner/banner_base", "banner/base");
        if (path == null) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        parts.add(Part.box(v(-2, 27, 13), v(18, 29, 15), 20, 2, 2, 0, 42, false));                 // crossbar
        parts.add(Part.box(v(-2, -13, 15), v(18, 27, 16), 20, 40, 1, 0, 0, false).tint(DYE_RGB[dye])); // cloth
        return r.renderParts(path, parts, new double[]{15, -25, 0}, size);
    }

    // ---------------------------------------------------------------- beds

    private static BufferedImage bed(ModelRenderer r, ModelRenderer.Source src, String id, int size) {
        int dye = dyeIndex(id, "_bed");
        if (dye < 0) {
            return null;
        }
        // Up to 1.21.x the bed is an entity texture; from 26.x it is two block models (red_bed_head / red_bed_foot in
        // the item definition), which the model renderer draws, so a missing sheet is not reported here.
        String path = src.findTexture("bed/" + DYES[dye], DYES[dye] + "_bed", "bed/" + DYES[dye] + "_bed");
        if (path == null) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        // The game's BedRenderer for the item (facing south): the bed lies along z, the head half (pillow) at z 0..16
        // and the foot half at z -16..0, mattress 6 high on 3 high legs. Each half is one 16x16x6 box of the sheet
        // (head at 0,0, foot at 0,22) turned flat, which is where these per-face rects and rotations come from.
        // The icon uses the model's own gui rotation (item/template_bed: 30, 160, 0): the foot end faces the viewer.
        double[][] legs = {{0, 13, 6}, {13, 13, 18}, {0, -16, 0}, {13, -16, 12}};   // x, z, texture v
        for (double[] leg : legs) {
            parts.add(Part.blockBox(v(leg[0], 0, leg[1]), v(leg[0] + 3, 3, leg[1] + 3), 3, 3, 3, 50, (int) leg[2]));
        }
        // head half: top, the head end (south), both long sides
        parts.add(new Part(v(0, 3, 0), v(16, 9, 16))
                .face(1, 6, 6, 22, 22, 180)      // top
                .face(3, 6, 0, 22, 6, 180)       // head end
                .face(5, 0, 6, 6, 22, 270)       // east side
                .face(4, 22, 6, 28, 22, 90));    // west side
        // foot half: top, the foot end (north, art runs bottom to top), both long sides
        parts.add(new Part(v(0, 3, -16), v(16, 9, 0))
                .face(1, 6, 28, 22, 44, 180)     // top
                .face(2, 22, 28, 38, 22, 0)      // foot end
                .face(5, 0, 28, 6, 44, 270)      // east side
                .face(4, 22, 28, 28, 44, 90));   // west side
        return r.renderParts(path, parts, BED_VIEW, size);
    }

    // ---------------------------------------------------------------- mob heads

    private static BufferedImage head(ModelRenderer r, ModelRenderer.Source src, String id, int size) {
        String path;
        boolean hat = false;
        switch (id) {
            case "skeleton_skull":
                path = find(src, "skeleton/skeleton");
                break;
            case "wither_skeleton_skull":
                path = find(src, "skeleton/wither_skeleton");
                break;
            case "zombie_head":
                path = find(src, "zombie/zombie");
                break;
            case "creeper_head":
                path = find(src, "creeper/creeper");
                break;
            case "player_head":
                path = find(src, "player/wide/steve", "player/steve", "steve");
                hat = true;
                break;
            default:
                return null; // piglin and dragon heads have their own shapes
        }
        if (path == null) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        parts.add(Part.box(v(4, 0, 4), v(12, 8, 12), 8, 8, 8, 0, 0, true));
        BufferedImage tex = src.texture(path);
        if (hat && tex != null && tex.getHeight() >= tex.getWidth()) {
            parts.add(Part.box(v(3.75, -0.25, 3.75), v(12.25, 8.25, 12.25), 8, 8, 8, 32, 0, true));
        }
        return r.renderParts(path, parts, new double[]{20, 200, 0}, size);
    }
}
