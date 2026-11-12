package com.fluxer.srv;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws a player's inventory the way the game's inventory screen shows it: the background is the client's own
 * gui/container/inventory.png (saved next to the item icon atlas), the items in it are the generated item icons,
 * with stack counts in the game's font and durability bars. If the texture is not available, a similar panel is
 * drawn in code, so the picture still works.
 */
public final class InventoryImage {

    private InventoryImage() {
    }

    /** One stack, copied from the server thread so the picture can be drawn later on another thread. */
    public static final class Slot {
        final String material;   // lower case id, e.g. "diamond_pickaxe"
        final int amount;
        final int damage;
        final int maxDamage;

        public Slot(String material, int amount, int damage, int maxDamage) {
            this.material = material;
            this.amount = amount;
            this.damage = damage;
            this.maxDamage = maxDamage;
        }
    }

    /**
     * Slot order, the same as Bukkit's PlayerInventory#getContents(): 0-8 hotbar, 9-35 main inventory,
     * 36-39 boots, leggings, chestplate, helmet, 40 off hand.
     */
    public static final int SLOTS = 41;

    private static final int GUI_W = 176;
    private static final int GUI_H = 166;

    /** Top-left of the 16x16 item area of a slot, in the inventory screen's own pixels. */
    private static int[] position(int index) {
        if (index < 9) {
            return new int[]{8 + index * 18, 142};
        }
        if (index < 36) {
            int n = index - 9;
            return new int[]{8 + (n % 9) * 18, 84 + (n / 9) * 18};
        }
        switch (index) {
            case 36:
                return new int[]{8, 62};   // boots
            case 37:
                return new int[]{8, 44};   // leggings
            case 38:
                return new int[]{8, 26};   // chestplate
            case 39:
                return new int[]{8, 8};    // helmet
            default:
                return new int[]{77, 62};  // off hand
        }
    }

    /**
     * The png of the inventory screen with these items in it, or null if it cannot be drawn. {@code player} is the
     * picture of the player (e.g. from heads.discordsrv.com/NAME/player) shown in the screen's player slot; null leaves
     * the slot empty.
     */
    static byte[] render(ItemIcons icons, Slot[] slots, BufferedImage player, int scale, java.util.logging.Logger log) {
        try {
            if (icons == null || slots == null) {
                return null;
            }
            if (!icons.hasIcons()) {
                if (log != null) {
                    log.warning("No [inv] picture: the item icons are not generated yet (plugins/FluxerSRV/icons/atlas.json "
                            + "is missing). Check the console for 'Item icons ready'.");
                }
                return null;
            }
            int s = Math.max(1, Math.min(8, scale));
            BufferedImage out = new BufferedImage(GUI_W * s, GUI_H * s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            try {
                drawBackground(g, icons.guiTexture("inventory"), s);
                drawPlayer(g, player, s);
                McFont font = McFont.load(icons.guiTexture("ascii"));
                Map<String, Image> scaledIcons = new HashMap<>();
                for (int i = 0; i < SLOTS && i < slots.length; i++) {
                    Slot slot = slots[i];
                    if (slot == null) {
                        continue;
                    }
                    int[] at = position(i);
                    drawItem(g, icons, scaledIcons, slot, at[0], at[1], s);
                    drawDurability(g, slot, at[0], at[1], s);
                    if (slot.amount > 1) {
                        font.draw(g, String.valueOf(slot.amount), at[0] + 17, at[1] + 9, s);
                    }
                }
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (Throwable t) {
            if (log != null && !failureLogged) {
                failureLogged = true;
                log.log(java.util.logging.Level.WARNING, "Could not draw the [inv] picture (the text list is sent instead): " + t, t);
            }
            return null;
        }
    }

    private static volatile boolean failureLogged;

    // ---------------------------------------------------------------- background

    private static void drawBackground(Graphics2D g, BufferedImage gui, int s) {
        if (gui != null && gui.getWidth() >= 256 && gui.getHeight() >= 166) {
            // the sheet is 256x256 for the vanilla pack; a texture pack may use a larger multiple of it
            int f = Math.max(1, gui.getWidth() / 256);
            BufferedImage panel = gui.getSubimage(0, 0, GUI_W * f, GUI_H * f);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(panel, 0, 0, GUI_W * s, GUI_H * s, null);
            return;
        }
        // fallback: the same panel and slots drawn in code
        rect(g, s, 0, 0, GUI_W, GUI_H, 0x000000);
        rect(g, s, 1, 1, GUI_W - 2, GUI_H - 2, 0xFFFFFF);
        rect(g, s, 3, 3, GUI_W - 4, GUI_H - 4, 0x555555);
        rect(g, s, 3, 3, GUI_W - 6, GUI_H - 6, 0xC6C6C6);
        bevel(g, s, 25, 7, 51, 72);
        for (int i = 0; i < SLOTS; i++) {
            int[] at = position(i);
            bevel(g, s, at[0] - 1, at[1] - 1, 18, 18);
        }
        // the crafting grid and its result, which the picture leaves empty
        bevel(g, s, 97, 17, 18, 18);
        bevel(g, s, 115, 17, 18, 18);
        bevel(g, s, 97, 35, 18, 18);
        bevel(g, s, 115, 35, 18, 18);
        bevel(g, s, 151, 21, 26, 26);
    }

    /** A sunken square (slot): dark top-left edge, light bottom-right edge, grey inside. */
    private static void bevel(Graphics2D g, int s, int x, int y, int w, int h) {
        rect(g, s, x, y, w, h, 0xFFFFFF);
        rect(g, s, x, y, w - 1, h - 1, 0x373737);
        rect(g, s, x + 1, y + 1, w - 2, h - 2, 0x8B8B8B);
    }

    private static void rect(Graphics2D g, int s, int x, int y, int w, int h, int rgb) {
        g.setColor(new Color(rgb));
        g.fillRect(x * s, y * s, w * s, h * s);
    }

    // ---------------------------------------------------------------- player picture

    /** The box of the inventory screen the player is shown in (inside its border), in the screen's own pixels. */
    private static final int PLAYER_X = 26;
    private static final int PLAYER_Y = 8;
    private static final int PLAYER_W = 49;
    private static final int PLAYER_H = 70;

    /** Fits the player picture into the player slot, keeping its proportions. */
    private static void drawPlayer(Graphics2D g, BufferedImage player, int s) {
        if (player == null || player.getWidth() < 1 || player.getHeight() < 1) {
            return;
        }
        int pad = 2;
        double fit = Math.min((PLAYER_W - 2 * pad) * (double) s / player.getWidth(),
                (PLAYER_H - 2 * pad) * (double) s / player.getHeight());
        int w = Math.max(1, (int) Math.round(player.getWidth() * fit));
        int h = Math.max(1, (int) Math.round(player.getHeight() * fit));
        BufferedImage scaled = scaleTo(player, w, h);
        int x = PLAYER_X * s + (PLAYER_W * s - w) / 2;
        int y = PLAYER_Y * s + (PLAYER_H * s - h) / 2;
        g.drawImage(scaled, x, y, null);
    }

    /** Scales to w x h with smooth filtering, halving step by step so large pictures stay sharp. */
    private static BufferedImage scaleTo(BufferedImage image, int w, int h) {
        BufferedImage current = image;
        while (current.getWidth() > w * 2 && current.getHeight() > h * 2) {
            current = resizeTo(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return current.getWidth() == w && current.getHeight() == h ? current : resizeTo(current, w, h);
    }

    private static BufferedImage resizeTo(BufferedImage image, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    // ---------------------------------------------------------------- shulker boxes and ender chests

    /** The 27 slots of a shulker box or an ender chest (3 rows of 9), in container order. */
    public static final int CONTAINER_SLOTS = 27;
    /** Texture names ({@link ItemIcons#guiTexture}) of the two container screens. */
    public static final String SHULKER_TEXTURE = "shulker_box";
    public static final String CHEST_TEXTURE = "generic_54";

    private static final int CONT_W = 176;
    private static final int CONT_TOP = 71;     // title strip + 3 rows of slots (17 + 3 * 18)
    private static final int CONT_EDGE = 6;     // bottom border of the panel, cut from the end of the texture
    private static final int CONT_H = CONT_TOP + CONT_EDGE;

    /**
     * The png of a shulker box / ender chest screen (the container part only, without the player's own
     * inventory) with these 27 slots in it, or null if it cannot be drawn. {@code texture} is
     * {@link #SHULKER_TEXTURE} or {@link #CHEST_TEXTURE}.
     */
    static byte[] renderContainer(ItemIcons icons, Slot[] slots, String texture, int scale,
                                  java.util.logging.Logger log) {
        try {
            if (icons == null || slots == null) {
                return null;
            }
            if (!icons.hasIcons()) {
                if (log != null) {
                    log.warning("No container picture: the item icons are not generated yet (plugins/FluxerSRV/icons/atlas.json "
                            + "is missing). Check the console for 'Item icons ready'.");
                }
                return null;
            }
            int s = Math.max(1, Math.min(8, scale));
            BufferedImage out = new BufferedImage(CONT_W * s, CONT_H * s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            try {
                // the game's texture sheets: content is 167 px high for the shulker box screen, 222 for the 6-row chest
                drawContainerBackground(g, icons.guiTexture(texture), SHULKER_TEXTURE.equals(texture) ? 167 : 222, s);
                McFont font = McFont.load(icons.guiTexture("ascii"));
                Map<String, Image> scaledIcons = new HashMap<>();
                for (int i = 0; i < CONTAINER_SLOTS && i < slots.length; i++) {
                    Slot slot = slots[i];
                    if (slot == null) {
                        continue;
                    }
                    int x = 8 + (i % 9) * 18;
                    int y = 18 + (i / 9) * 18;
                    drawItem(g, icons, scaledIcons, slot, x, y, s);
                    drawDurability(g, slot, x, y, s);
                    if (slot.amount > 1) {
                        font.draw(g, String.valueOf(slot.amount), x + 17, y + 9, s);
                    }
                }
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return bytes.toByteArray();
        } catch (Throwable t) {
            if (log != null && !failureLogged) {
                failureLogged = true;
                log.log(java.util.logging.Level.WARNING, "Could not draw a container picture (the text list is sent instead): " + t, t);
            }
            return null;
        }
    }

    private static void drawContainerBackground(Graphics2D g, BufferedImage gui, int contentH, int s) {
        if (gui != null && gui.getWidth() >= 256) {
            int f = Math.max(1, gui.getWidth() / 256);
            if (gui.getHeight() >= contentH * f) {
                // the top of the screen (title + 3 rows) and, below it, the panel's bottom border
                BufferedImage top = gui.getSubimage(0, 0, CONT_W * f, CONT_TOP * f);
                BufferedImage edge = gui.getSubimage(0, (contentH - CONT_EDGE) * f, CONT_W * f, CONT_EDGE * f);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(top, 0, 0, CONT_W * s, CONT_TOP * s, null);
                g.drawImage(edge, 0, CONT_TOP * s, CONT_W * s, CONT_EDGE * s, null);
                return;
            }
        }
        // fallback: the same panel and slots drawn in code
        rect(g, s, 0, 0, CONT_W, CONT_H, 0x000000);
        rect(g, s, 1, 1, CONT_W - 2, CONT_H - 2, 0xFFFFFF);
        rect(g, s, 3, 3, CONT_W - 4, CONT_H - 4, 0x555555);
        rect(g, s, 3, 3, CONT_W - 6, CONT_H - 6, 0xC6C6C6);
        for (int i = 0; i < CONTAINER_SLOTS; i++) {
            bevel(g, s, 7 + (i % 9) * 18, 17 + (i / 9) * 18, 18, 18);
        }
    }

    // ---------------------------------------------------------------- items

    private static void drawItem(Graphics2D g, ItemIcons icons, Map<String, Image> cache, Slot slot,
                                 int x, int y, int s) {
        Image scaled = cache.get(slot.material);
        if (scaled == null && !cache.containsKey(slot.material)) {
            BufferedImage icon = icons.icon(slot.material);
            if (icon != null) {
                scaled = scale(icon, 16 * s);
            }
            cache.put(slot.material, scaled);
        }
        if (scaled != null) {
            g.drawImage(scaled, x * s, y * s, null);
        }
    }

    /** Scales an icon to size x size with smooth filtering, halving step by step so large icons stay sharp. */
    private static BufferedImage scale(BufferedImage image, int size) {
        BufferedImage current = image;
        while (current.getWidth() > size * 2) {
            current = resize(current, current.getWidth() / 2);
        }
        return current.getWidth() == size ? current : resize(current, size);
    }

    private static BufferedImage resize(BufferedImage image, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** The bar under a damaged tool or armor piece, as the game draws it. */
    private static void drawDurability(Graphics2D g, Slot slot, int x, int y, int s) {
        if (slot.maxDamage <= 0 || slot.damage <= 0) {
            return;
        }
        double left = Math.max(0.0, Math.min(1.0, (slot.maxDamage - slot.damage) / (double) slot.maxDamage));
        int width = (int) Math.round(13.0 - slot.damage * 13.0 / slot.maxDamage);
        width = Math.max(0, Math.min(13, width));
        rect(g, s, x + 2, y + 13, 13, 2, 0x000000);
        rect(g, s, x + 2, y + 13, width, 1, Color.HSBtoRGB((float) left / 3.0f, 1.0f, 1.0f) & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- stack count font

    /** The game's own font (font/ascii.png) for the stack counts, or a plain bold font if it is missing. */
    private static final class McFont {
        private final BufferedImage sheet;   // null: use the AWT font

        private McFont(BufferedImage sheet) {
            this.sheet = sheet;
        }

        static McFont load(BufferedImage sheet) {
            return new McFont(sheet != null && sheet.getWidth() >= 16 && sheet.getWidth() == sheet.getHeight()
                    ? sheet : null);
        }

        /** Pixels from the cell's left edge to the end of the glyph (plus one pixel of spacing), in sheet pixels. */
        private int advance(char c) {
            int cell = sheet.getWidth() / 16;
            int cx = (c % 16) * cell;
            int cy = (c / 16) * cell;
            int last = -1;
            for (int px = 0; px < cell; px++) {
                for (int py = 0; py < cell; py++) {
                    if ((sheet.getRGB(cx + px, cy + py) >>> 24) != 0) {
                        last = px;
                    }
                }
            }
            return last < 0 ? cell / 2 : last + 1;
        }

        /** Draws {@code text} with its right edge at (rightX) and its top at (top), in inventory pixels, with a shadow. */
        void draw(Graphics2D g, String text, int rightX, int top, int s) {
            if (sheet == null) {
                drawFallback(g, text, rightX, top, s);
                return;
            }
            int cell = sheet.getWidth() / 16;
            double unit = 8.0 / cell;                 // one sheet pixel in inventory pixels
            int[] adv = new int[text.length()];
            double width = 0;
            for (int i = 0; i < text.length(); i++) {
                adv[i] = advance(text.charAt(i)) + (cell / 8);   // glyph plus the one pixel gap, hi-res aware
                width += adv[i] * unit;
            }
            for (int pass = 0; pass < 2; pass++) {
                int rgb = pass == 0 ? 0x3F3F3F : 0xFFFFFF;
                double shift = pass == 0 ? 1 : 0;
                double pen = rightX - width;
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    int cx = (c % 16) * cell;
                    int cy = (c / 16) * cell;
                    for (int px = 0; px < cell; px++) {
                        for (int py = 0; py < cell; py++) {
                            if ((sheet.getRGB(cx + px, cy + py) >>> 24) == 0) {
                                continue;
                            }
                            g.setColor(new Color(rgb));
                            int x1 = (int) Math.round((pen + px * unit + shift) * s);
                            int y1 = (int) Math.round((top + py * unit + shift) * s);
                            int x2 = (int) Math.round((pen + (px + 1) * unit + shift) * s);
                            int y2 = (int) Math.round((top + (py + 1) * unit + shift) * s);
                            g.fillRect(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
                        }
                    }
                    pen += adv[i] * unit;
                }
            }
        }

        /** Built-in 5x7 digits, used when the font sheet is missing; needs no system fonts (servers often have none). */
        private static final String[][] DIGITS = {
                {"01110", "10001", "10001", "10001", "10001", "10001", "01110"},
                {"00100", "01100", "00100", "00100", "00100", "00100", "01110"},
                {"01110", "10001", "00001", "00010", "00100", "01000", "11111"},
                {"01110", "10001", "00001", "00110", "00001", "10001", "01110"},
                {"00010", "00110", "01010", "10010", "11111", "00010", "00010"},
                {"11111", "10000", "11110", "00001", "00001", "10001", "01110"},
                {"00110", "01000", "10000", "11110", "10001", "10001", "01110"},
                {"11111", "00001", "00010", "00100", "01000", "01000", "01000"},
                {"01110", "10001", "10001", "01110", "10001", "10001", "01110"},
                {"01110", "10001", "10001", "01111", "00001", "00010", "01100"}};

        private void drawFallback(Graphics2D g, String text, int rightX, int top, int s) {
            int pen = rightX - text.length() * 6;
            for (int pass = 0; pass < 2; pass++) {
                g.setColor(new Color(pass == 0 ? 0x3F3F3F : 0xFFFFFF));
                int shift = pass == 0 ? 1 : 0;
                int x = pen;
                for (int i = 0; i < text.length(); i++, x += 6) {
                    char c = text.charAt(i);
                    if (c < '0' || c > '9') {
                        continue;
                    }
                    String[] rows = DIGITS[c - '0'];
                    for (int row = 0; row < rows.length; row++) {
                        for (int col = 0; col < 5; col++) {
                            if (rows[row].charAt(col) == '1') {
                                g.fillRect((x + col + shift) * s, (top + 1 + row + shift) * s, s, s);
                            }
                        }
                    }
                }
            }
        }
    }
}
