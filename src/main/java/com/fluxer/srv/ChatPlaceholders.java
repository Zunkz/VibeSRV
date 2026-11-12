package com.fluxer.srv;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * InteractiveChat-style placeholders for Minecraft -> Fluxer chat.
 *
 * Fluxer can't show InteractiveChat's rendered images, so shared items, inventories, ender chests
 * and custom placeholders are posted as Fluxer markdown: an inline code tag in the message text
 * (e.g. {@code `[Diamond Sword]`}) and a code block with the details after it.
 * Works with or without the InteractiveChat plugin installed; when it is installed its
 * permissions (interactivechat.module.*) are respected.
 */
public final class ChatPlaceholders {

    /** The player's message with placeholders replaced, plus the detail blocks to append after it. */
    public static final class Result {
        public final String message;
        public final String details;
        /** Embeds ([item], [inv], [ender], shulker contents) to post with the message; empty if none. */
        public final List<Embed> embeds;

        Result(String message, String details, List<Embed> embeds) {
            this.message = message;
            this.details = details;
            this.embeds = embeds;
        }
    }

    /** One embed: title (the author line, no icon), body (markdown text) and side-bar color. */
    public static final class Embed {
        public final String title;
        public final String body;
        public final int color;
        /** Material name in lower case (e.g. "diamond_pickaxe") of the item shown, for its thumbnail; null if none. */
        public String material;
        /** The player's inventory (see {@link InventoryImage#SLOTS}) to draw as a picture; null if none. */
        public InventoryImage.Slot[] slots;
        /** Name of the player whose picture goes in the [inv] picture's player slot; null if none. */
        public String playerName;
        /** The 27 slots of a shulker box / ender chest to draw as a picture; null if none. */
        public InventoryImage.Slot[] containerSlots;
        /** Texture of that container's screen ({@link InventoryImage#SHULKER_TEXTURE} / {@link InventoryImage#CHEST_TEXTURE}). */
        public String containerTexture;
        /** Config section (enderchest or shulker) holding that picture's settings under embed.image. */
        public String containerSection;

        Embed(String title, String body, int color) {
            this.title = title;
            this.body = body;
            this.color = color;
        }
    }

    // order of PlayerInventory#getArmorContents()
    private static final String[] ARMOR_LABELS = {"Boots", "Leggings", "Chestplate", "Helmet"};
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    private static final Map<String, String> ENCHANTS = new HashMap<>();

    static {
        ENCHANTS.put("PROTECTION_ENVIRONMENTAL", "Protection");
        ENCHANTS.put("PROTECTION_FIRE", "Fire Protection");
        ENCHANTS.put("PROTECTION_FALL", "Feather Falling");
        ENCHANTS.put("PROTECTION_EXPLOSIONS", "Blast Protection");
        ENCHANTS.put("PROTECTION_PROJECTILE", "Projectile Protection");
        ENCHANTS.put("OXYGEN", "Respiration");
        ENCHANTS.put("WATER_WORKER", "Aqua Affinity");
        ENCHANTS.put("DAMAGE_ALL", "Sharpness");
        ENCHANTS.put("DAMAGE_UNDEAD", "Smite");
        ENCHANTS.put("DAMAGE_ARTHROPODS", "Bane of Arthropods");
        ENCHANTS.put("LOOT_BONUS_MOBS", "Looting");
        ENCHANTS.put("LOOT_BONUS_BLOCKS", "Fortune");
        ENCHANTS.put("DIG_SPEED", "Efficiency");
        ENCHANTS.put("DURABILITY", "Unbreaking");
        ENCHANTS.put("ARROW_DAMAGE", "Power");
        ENCHANTS.put("ARROW_KNOCKBACK", "Punch");
        ENCHANTS.put("ARROW_FIRE", "Flame");
        ENCHANTS.put("ARROW_INFINITE", "Infinity");
        ENCHANTS.put("LUCK", "Luck of the Sea");
        ENCHANTS.put("BINDING_CURSE", "Curse of Binding");
        ENCHANTS.put("VANISHING_CURSE", "Curse of Vanishing");
        ENCHANTS.put("SWEEPING", "Sweeping Edge");
        // modern namespaced keys that differ from a plain title-cased key
        ENCHANTS.put("SWEEPING_EDGE", "Sweeping Edge");
        ENCHANTS.put("LUCK_OF_THE_SEA", "Luck of the Sea");
        ENCHANTS.put("BANE_OF_ARTHROPODS", "Bane of Arthropods");
    }

    private final FluxerSRV plugin;
    // created in onEnable(), which runs on the main server thread
    private final Thread mainThread = Thread.currentThread();

    public ChatPlaceholders(FluxerSRV plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public boolean isEnabled() {
        return cfg().getBoolean("interactivechat.enabled", true);
    }

    // ---- detection ----

    private String keyword(String section, String def) {
        String key = cfg().getString("interactivechat." + section + ".keyword", def);
        return key == null ? "" : key;
    }

    private boolean sectionEnabled(String section) {
        return cfg().getBoolean("interactivechat." + section + ".enabled", true)
                && !keyword(section, "").isEmpty();
    }

    /** Cheap check, safe from any thread: could this message contain a placeholder? */
    public boolean containsKeyword(String text) {
        if (!isEnabled() || text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String[][] builtin = {{"item", "[item]"}, {"inventory", "[inv]"}, {"enderchest", "[ender]"}};
        for (String[] entry : builtin) {
            if (sectionEnabled(entry[0]) && lower.contains(keyword(entry[0], entry[1]).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        for (Map<?, ?> custom : cfg().getMapList("interactivechat.custom")) {
            Object key = custom.get("keyword");
            if (key != null && !String.valueOf(key).isEmpty()
                    && lower.contains(String.valueOf(key).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ---- processing ----

    /** Runs {@link #process} on the main thread from any thread. Returns null on failure. */
    public Result processSafely(Player player, String text) {
        try {
            if (Thread.currentThread() == mainThread) {
                return process(player, text);
            }
            Future<Result> future = plugin.getServer().getScheduler()
                    .callSyncMethod(plugin, () -> process(player, text));
            return future.get(3, TimeUnit.SECONDS);
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            plugin.getLogger().warning("Could not process chat placeholders: " + cause);
            for (StackTraceElement line : cause.getStackTrace()) {
                if (line.getClassName().startsWith("com.fluxer")) {
                    plugin.getLogger().warning("    at " + line);
                    break;
                }
            }
            return null;
        }
    }

    /** Must be called on the main server thread (reads inventories). */
    public Result process(Player player, String text) {
        String out = text;
        List<String> blocks = new ArrayList<>();
        List<Embed> embeds = new ArrayList<>();
        int max = maxLength();

        if (sectionEnabled("item") && contains(out, keyword("item", "[item]"))
                && allowed(player, "interactivechat.module.item", null)) {
            ItemStack held = player.getItemInHand();
            String inline;
            if (isEmpty(held)) {
                inline = plugin.papi(player, cfg().getString("interactivechat.item.empty-inline", "`[Nothing]`"));
            } else {
                inline = plugin.papi(player, cfg().getString("interactivechat.item.inline", "`[%item%]`"))
                        .replace("%item%", itemTag(held))
                        .replace("%player%", safe(player.getName()));
                if (shulkerAsEmbed(held)) {
                    inline = ""; // the embed replaces the tag and the details block
                    embeds.add(shulkerEmbed(player, held, max));
                } else if (embedEnabled("item")) {
                    inline = ""; // the embed replaces the tag and the details block
                    embeds.add(itemEmbed(player, held, max));
                } else {
                    blocks.add(codeBlock(max, itemLines(held, true)));
                }
            }
            out = replaceIgnoreCase(out, keyword("item", "[item]"), inline);
        }

        if (sectionEnabled("inventory") && contains(out, keyword("inventory", "[inv]"))
                && allowed(player, "interactivechat.module.inventory", null)) {
            if (embedEnabled("inventory")) {
                // the embed replaces the inline tag and the details block
                out = replaceIgnoreCase(out, keyword("inventory", "[inv]"), "");
                embeds.add(inventoryEmbed(player, max));
            } else {
                String inline = plugin.papi(player, cfg().getString("interactivechat.inventory.inline", "`[%player%'s Inventory]`"))
                        .replace("%player%", safe(player.getName()));
                out = replaceIgnoreCase(out, keyword("inventory", "[inv]"), inline);
                blocks.add(codeBlock(max, inventoryLines(player)));
            }
        }

        if (sectionEnabled("enderchest") && contains(out, keyword("enderchest", "[ender]"))
                && allowed(player, "interactivechat.module.enderchest", null)) {
            if (embedEnabled("enderchest")) {
                // the embed replaces the inline tag and the details block
                out = replaceIgnoreCase(out, keyword("enderchest", "[ender]"), "");
                embeds.add(enderEmbed(player, max));
            } else {
                String inline = plugin.papi(player, cfg().getString("interactivechat.enderchest.inline", "`[%player%'s Ender Chest]`"))
                        .replace("%player%", safe(player.getName()));
                out = replaceIgnoreCase(out, keyword("enderchest", "[ender]"), inline);
                blocks.add(codeBlock(max, enderLines(player)));
            }
        }

        for (Map<?, ?> custom : cfg().getMapList("interactivechat.custom")) {
            Object keyObject = custom.get("keyword");
            String key = keyObject == null ? "" : String.valueOf(keyObject);
            if (key.isEmpty() || !contains(out, key)) {
                continue;
            }
            Object permObject = custom.get("permission");
            String permission = permObject == null ? null : String.valueOf(permObject);
            if (!allowed(player, "interactivechat.module.custom", permission)) {
                continue;
            }

            // An embed section turns the placeholder into an embed (e.g. [pos] -> position card)
            Object embedObject = custom.get("embed");
            if (embedObject instanceof Map && cfg().getBoolean("embeds.enabled", true)
                    && !"false".equalsIgnoreCase(String.valueOf(((Map<?, ?>) embedObject).get("enabled")))) {
                embeds.add(customEmbed(player, (Map<?, ?>) embedObject, max));
                out = replaceIgnoreCase(out, key, "");
                continue;
            }

            Object replaceObject = custom.get("replace");
            String replacement = replaceObject == null ? key : defuse(expand(player, String.valueOf(replaceObject)));

            Object clickObject = custom.get("click-url");
            String url = clickObject == null ? "" : String.valueOf(clickObject).trim();
            if (url.toLowerCase(Locale.ROOT).startsWith("http://") || url.toLowerCase(Locale.ROOT).startsWith("https://")) {
                replacement = "[" + replacement + "](" + url + ")";
            }
            out = replaceIgnoreCase(out, key, replacement);

            List<String> hover = new ArrayList<>();
            Object hoverObject = custom.get("hover");
            if (hoverObject instanceof Collection) {
                for (Object line : (Collection<?>) hoverObject) {
                    hover.add(safe(expand(player, String.valueOf(line))));
                }
            } else if (hoverObject != null) {
                for (String line : String.valueOf(hoverObject).split("\n")) {
                    hover.add(safe(expand(player, line)));
                }
            }
            if (!hover.isEmpty()) {
                blocks.add(codeBlock(max, hover));
            }
        }

        StringBuilder details = new StringBuilder();
        for (String block : blocks) {
            if (details.length() > 0) {
                details.append('\n');
            }
            details.append(block);
        }
        if (!embeds.isEmpty()) {
            out = out.replaceAll(" {2,}", " ").trim(); // placeholders shown as embeds leave no text behind
        }
        return new Result(out, details.toString(), embeds);
    }

    // ---- embeds ([inv], [ender], shulker box contents) ----

    /** interactivechat.<section>.embed.enabled (on by default): section is item, inventory, enderchest or shulker. */
    private boolean embedEnabled(String section) {
        return cfg().getBoolean("embeds.enabled", true)
                && cfg().getBoolean("interactivechat." + section + ".embed.enabled", true);
    }

    private Embed makeEmbed(String section, String defaultTitle, Player player, String itemName,
                            List<String> bodyLines, boolean headings, int max) {
        String title = plugin.papi(player, cfg().getString("interactivechat." + section + ".embed.title", defaultTitle))
                .replace("%player%", safe(player.getName()))
                .replace("%item%", safe(itemName == null ? "" : itemName));
        int color = EventEmbeds.parseColor(cfg().get("interactivechat." + section + ".embed.color"), 0x5865F2);
        return new Embed(title, embedText(max, bodyLines, headings), color);
    }

    /** An embed for a custom placeholder: title, color and body lines, all with PlaceholderAPI support. */
    private Embed customEmbed(Player player, Map<?, ?> section, int max) {
        Object titleObject = section.get("title");
        String title = safe(expand(player, titleObject == null ? "%player%" : String.valueOf(titleObject)));
        int color = EventEmbeds.parseColor(section.get("color"), 0x5865F2);

        List<String> lines = new ArrayList<>();
        Object bodyObject = section.get("body");
        if (bodyObject instanceof Collection) {
            for (Object line : (Collection<?>) bodyObject) {
                lines.add(String.valueOf(line));
            }
        } else if (bodyObject != null) {
            lines.addAll(java.util.Arrays.asList(String.valueOf(bodyObject).split("\n")));
        }
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            String text = defuse(expand(player, line));
            if (body.length() + text.length() + 1 > max) {
                body.append("\n... (truncated)");
                break;
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(text);
        }
        return new Embed(title, body.toString(), color);
    }

    /** Lines without the first one, which is the "X's Inventory" heading the embed title replaces. */
    private static List<String> withoutHeading(List<String> lines) {
        return new ArrayList<>(lines.subList(1, lines.size()));
    }

    private Embed inventoryEmbed(Player player, int max) {
        Embed embed = makeEmbed("inventory", "%player%'s Inventory", player, null,
                withoutHeading(inventoryLines(player)), true, max);
        if (cfg().getBoolean("interactivechat.inventory.embed.image.enabled", true)) {
            embed.slots = inventorySlots(player);
            embed.playerName = player.getName();
        }
        return embed;
    }

    /** The stacks to draw in the [inv] picture, copied now because the picture is made on another thread. */
    private static InventoryImage.Slot[] inventorySlots(Player player) {
        InventoryImage.Slot[] slots = new InventoryImage.Slot[InventoryImage.SLOTS];
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            slots[i] = slotOf(inventory.getItem(i));
        }
        ItemStack[] armor = inventory.getArmorContents(); // boots, leggings, chestplate, helmet
        for (int i = 0; i < 4 && i < armor.length; i++) {
            slots[36 + i] = slotOf(armor[i]);
        }
        Object offHand = call(inventory, "getItemInOffHand"); // 1.9+
        if (offHand instanceof ItemStack) {
            slots[40] = slotOf((ItemStack) offHand);
        }
        return slots;
    }

    private static InventoryImage.Slot slotOf(ItemStack item) {
        if (isEmpty(item)) {
            return null;
        }
        return new InventoryImage.Slot(item.getType().name().toLowerCase(Locale.ROOT), item.getAmount(),
                damage(item), item.getType().getMaxDurability());
    }

    private Embed enderEmbed(Player player, int max) {
        Embed embed = makeEmbed("enderchest", "%player%'s Ender Chest", player, null,
                withoutHeading(enderLines(player)), false, max);
        embed.material = "ender_chest"; // the block icon is the small thumbnail
        if (cfg().getBoolean("interactivechat.enderchest.embed.image.enabled", true)) {
            InventoryImage.Slot[] slots = new InventoryImage.Slot[InventoryImage.CONTAINER_SLOTS];
            Inventory chest = player.getEnderChest();
            for (int i = 0; i < slots.length && i < chest.getSize(); i++) {
                slots[i] = slotOf(chest.getItem(i));
            }
            embed.containerSlots = slots; // the contents are the big picture
            embed.containerTexture = InventoryImage.CHEST_TEXTURE;
            embed.containerSection = "enderchest";
        }
        return embed;
    }

    /** The 27 slots of a shulker box item in container order (empty slots stay null). */
    private static InventoryImage.Slot[] shulkerSlots(ItemStack box) {
        InventoryImage.Slot[] slots = new InventoryImage.Slot[InventoryImage.CONTAINER_SLOTS];
        try {
            ItemMeta meta = box.hasItemMeta() ? box.getItemMeta() : null;
            if (meta instanceof BlockStateMeta) {
                BlockState state = ((BlockStateMeta) meta).getBlockState();
                if (state instanceof InventoryHolder) {
                    ItemStack[] contents = ((InventoryHolder) state).getInventory().getContents();
                    for (int i = 0; i < slots.length && i < contents.length; i++) {
                        slots[i] = slotOf(contents[i]);
                    }
                }
            }
        } catch (Throwable ignored) {
            // unsupported on this server version
        }
        return slots;
    }

    /** A held item (any item, including bundles) with its enchantments, lore, durability and contents. */
    private Embed itemEmbed(Player player, ItemStack item, int max) {
        Embed embed = makeEmbed("item", "%player%'s Item", player, itemName(item),
                itemLines(item, true), true, max);
        embed.material = item.getType().name().toLowerCase(Locale.ROOT);
        return embed;
    }

    private Embed shulkerEmbed(Player player, ItemStack box, int max) {
        Embed embed = makeEmbed("shulker", "%player%'s %item%", player, itemName(box),
                contentLines(containedItems(box), ""), false, max);
        embed.material = box.getType().name().toLowerCase(Locale.ROOT); // the box icon is the small thumbnail
        if (cfg().getBoolean("interactivechat.shulker.embed.image.enabled", true)) {
            embed.containerSlots = shulkerSlots(box); // the contents are the big picture
            embed.containerTexture = InventoryImage.SHULKER_TEXTURE;
            embed.containerSection = "shulker";
        }
        return embed;
    }

    /**
     * Embed body as normal text (no code block). Markdown drops leading spaces, so the layout is
     * rebuilt: unindented lines become "## " headings (when {@code headings}), the rest bullets.
     */
    private static String embedText(int max, List<String> lines, boolean headings) {
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String text = line.substring(indent);
            if (text.startsWith("- ")) {
                text = text.substring(2);
            }
            text = text.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                    .replace("~", "\\~").replace("|", "\\|");
            StringBuilder out = new StringBuilder();
            if (headings && indent == 0) {
                out.append("## ").append(text);
            } else {
                int level = Math.max(0, (indent - 2) / 2);
                for (int i = 0; i < level; i++) {
                    out.append('\u2003').append('\u2003');
                }
                out.append("\u2022 ").append(text);
            }
            if (body.length() + out.length() + 1 > max) {
                body.append("... (truncated)");
                break;
            }
            body.append(out).append('\n');
        }
        String text = body.toString();
        while (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static boolean isShulker(ItemStack item) {
        return item.getType().name().endsWith("SHULKER_BOX");
    }

    /** True when this held item is a shulker box with contents and the shulker embed is switched on. */
    private boolean shulkerAsEmbed(ItemStack item) {
        return embedEnabled("shulker") && isShulker(item) && !containedItems(item).isEmpty();
    }

    private int maxLength() {
        return Math.max(200, cfg().getInt("interactivechat.max-details-length", 1900));
    }

    private static boolean contains(String text, String key) {
        return !key.isEmpty() && text.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT));
    }

    private static String replaceIgnoreCase(String text, String key, String replacement) {
        return Pattern.compile(Pattern.quote(key), Pattern.CASE_INSENSITIVE).matcher(text)
                .replaceAll(Matcher.quoteReplacement(replacement));
    }

    /**
     * InteractiveChat registers interactivechat.module.* permissions; honour them when it is
     * installed. An explicit permission from the config is always checked.
     */
    private boolean allowed(Player player, String defaultPermission, String explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return player.hasPermission(explicit);
        }
        if (plugin.getServer().getPluginManager().getPermission(defaultPermission) == null) {
            return true; // InteractiveChat not installed
        }
        return player.hasPermission(defaultPermission);
    }

    // ---- Fluxer text commands (!item, !inv, !ender, !playerlist, !time) ----

    /**
     * Handles a message typed in the Fluxer global channel as a command. Returns true if it was
     * one (so it shouldn't be shown in-game). Off unless interactivechat.commands.enabled.
     */
    public boolean handleFluxerCommand(String channelId, String content) {
        if (!cfg().getBoolean("interactivechat.commands.enabled", false)) {
            return false;
        }
        String prefix = cfg().getString("interactivechat.commands.prefix", "!");
        String trimmed = content.trim();
        if (prefix == null || prefix.isEmpty() || !trimmed.startsWith(prefix)) {
            return false;
        }
        String[] parts = trimmed.substring(prefix.length()).trim().split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);
        boolean general = command.equals("playerlist") || command.equals("time");
        boolean placeholder = command.equals("item") || command.equals("inv") || command.equals("ender");
        if (!general && !(placeholder && isEnabled())) {
            return false;
        }
        String argument = parts.length > 1 ? parts[1] : "";

        if (general) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                final String text = command.equals("time") ? timeText() : playerListText();
                final String colorKey = command.equals("time") ? "time" : "playerlist";
                final int defaultColor = command.equals("time") ? 0xF1C40F : 0x5865F2;
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        EventEmbeds.send(plugin, channelId, text, colorKey, defaultColor);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to send command reply to Fluxer: " + e.getMessage());
                    }
                });
            });
            return true;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player target = argument.isEmpty() ? null : plugin.getServer().getPlayerExact(argument);
            if (target != null) {
                int max = maxLength();
                List<Embed> embeds = new ArrayList<>();
                String text = "";
                if (command.equals("inv") && embedEnabled("inventory")) {
                    embeds.add(inventoryEmbed(target, max));
                } else if (command.equals("ender") && embedEnabled("enderchest")) {
                    embeds.add(enderEmbed(target, max));
                } else if (command.equals("item")) {
                    ItemStack held = target.getItemInHand();
                    if (!isEmpty(held)) {
                        if (shulkerAsEmbed(held)) {
                            embeds.add(shulkerEmbed(target, held, max));
                        } else if (embedEnabled("item")) {
                            embeds.add(itemEmbed(target, held, max));
                        }
                    }
                }
                if (!embeds.isEmpty()) {
                    final String embedText = text;
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            List<FluxerRestApi.Attachment> files = new ArrayList<>();
                            plugin.getFluxerClient().getRestApi().sendMessage(channelId, embedText,
                                    plugin.buildEmbeds(embeds, files), files);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to send command reply to Fluxer: " + e.getMessage());
                        }
                    });
                    return;
                }
            }
            String reply = commandReply(prefix, command, argument);
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.getFluxerClient().getRestApi().sendMessage(channelId, reply);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to send command reply to Fluxer: " + e.getMessage());
                }
            });
        });
        return true;
    }

    /** The player list embed text. Format, entry and empty text come from interactivechat.commands.*. */
    private String playerListText() {
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        Collections.sort(players, new java.util.Comparator<Player>() {
            @Override
            public int compare(Player a, Player b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        String title = plugin.papi(null, cfg().getString("interactivechat.commands.playerlist-title",
                "## Players Online (%online%/%max%)"))
                .replace("%online%", String.valueOf(players.size()))
                .replace("%max%", String.valueOf(plugin.getServer().getMaxPlayers()));
        StringBuilder text = new StringBuilder(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', title)));
        if (players.isEmpty()) {
            text.append('\n').append(cfg().getString("interactivechat.commands.playerlist-empty", "*Nobody is online*"));
        }
        String entry = cfg().getString("interactivechat.commands.playerlist-entry", "- %player_name%");
        for (Player player : players) {
            text.append('\n').append(defuse(expand(player, entry)));
        }
        return text.length() > 4000 ? text.substring(0, 3990) + "\n..." : text.toString();
    }

    /** The in-game time embed text. Format comes from interactivechat.commands.time-format. */
    private String timeText() {
        World world = plugin.getServer().getWorlds().get(0);
        String configured = cfg().getString("interactivechat.commands.time-world", "");
        if (configured != null && !configured.isEmpty()) {
            World named = plugin.getServer().getWorld(configured);
            if (named != null) {
                world = named;
            }
        }
        long ticks = world.getTime() % 24000L;
        int hour24 = (int) ((ticks / 1000L + 6L) % 24L);
        int minute = (int) ((ticks % 1000L) * 60L / 1000L);
        int hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
        String phase;
        if (ticks >= 23000L || ticks < 1000L) {
            phase = "Sunrise";
        } else if (ticks < 12000L) {
            phase = "Day";
        } else if (ticks < 13000L) {
            phase = "Sunset";
        } else {
            phase = "Night";
        }
        String clock = hour12 + ":" + (minute < 10 ? "0" : "") + minute + " " + (hour24 < 12 ? "AM" : "PM");
        String format = plugin.papi(null, cfg().getString("interactivechat.commands.time-format",
                "## In-Game Time\n**%time%** - %phase%\nDay %day% | %world%"));
        return format.replace("\\n", "\n")
                .replace("%time%", clock)
                .replace("%phase%", phase)
                .replace("%day%", String.valueOf(world.getFullTime() / 24000L + 1L))
                .replace("%world%", safe(world.getName()));
    }

    private String commandReply(String prefix, String command, String argument) {
        int max = maxLength();
        if (argument.isEmpty()) {
            return "Usage: `" + safe(prefix) + command + " <player>`";
        }
        Player target = plugin.getServer().getPlayerExact(argument);
        if (target == null) {
            return "`" + safe(argument) + "` is not online.";
        }
        if (command.equals("item")) {
            ItemStack held = target.getItemInHand();
            if (isEmpty(held)) {
                return "**" + safe(target.getName()) + "** isn't holding anything.";
            }
            return codeBlock(max, itemLines(held, true));
        }
        if (command.equals("inv")) {
            return codeBlock(max, inventoryLines(target));
        }
        return codeBlock(max, enderLines(target));
    }

    // ---- extra built-in placeholders for the position embed ----

    private static final String[] COMPASS = {"South", "South-West", "West", "North-West", "North", "North-East", "East", "South-East"};

    /** Compass direction the player is looking in, e.g. "North-East". */
    private static String facing(Player player) {
        float yaw = (player.getLocation().getYaw() % 360 + 360) % 360;
        return COMPASS[Math.round(yaw / 45f) % 8];
    }

    /** Biome name in words, e.g. "Dark Forest". */
    private static String biome(Player player) {
        try {
            String name = player.getLocation().getBlock().getBiome().name().toLowerCase(Locale.ROOT);
            StringBuilder out = new StringBuilder();
            for (String word : name.split("_")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return out.toString();
        } catch (Throwable e) {
            return "Unknown";
        }
    }

    private static String dimension(Player player) {
        switch (player.getWorld().getEnvironment()) {
            case NETHER:
                return "Nether";
            case THE_END:
                return "The End";
            default:
                return "Overworld";
        }
    }

    // ---- placeholder expansion ----

    private String expand(Player player, String text) {
        String s = text
                .replace("%player%", player.getName())
                .replace("%player_name%", player.getName())
                .replace("%player_displayname%", ChatColor.stripColor(player.getDisplayName()))
                .replace("%player_world%", player.getWorld().getName())
                .replace("%player_x%", String.valueOf(player.getLocation().getBlockX()))
                .replace("%player_y%", String.valueOf(player.getLocation().getBlockY()))
                .replace("%player_z%", String.valueOf(player.getLocation().getBlockZ()))
                .replace("%player_facing%", facing(player))
                .replace("%player_biome%", biome(player))
                .replace("%player_dimension%", dimension(player));
        s = plugin.papi(player, s);
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s));
    }

    // ---- items ----

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private static String pretty(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static boolean hasCustomName(ItemStack item) {
        try {
            ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
            return meta != null && meta.hasDisplayName() && !safe(meta.getDisplayName()).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String itemName(ItemStack item) {
        if (hasCustomName(item)) {
            return safe(item.getItemMeta().getDisplayName());
        }
        return ItemNames.baseName(item);
    }

    /** "Diamond Sword" or "Cobblestone x64". */
    private static String itemTag(ItemStack item) {
        String name = itemName(item);
        return item.getAmount() > 1 ? name + " x" + item.getAmount() : name;
    }

    /** Finds a no-arg method declared by a PUBLIC class/interface (CraftBukkit's own classes are not public). */
    private static Method publicMethod(Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                // keep looking in supertypes
            }
        }
        for (Class<?> iface : type.getInterfaces()) {
            Method found = publicMethod(iface, name);
            if (found != null) {
                return found;
            }
        }
        return publicMethod(type.getSuperclass(), name);
    }

    /** Calls a no-arg method via reflection (works across server versions); null if unavailable. */
    private static Object call(Object target, String method) {
        try {
            Method m = publicMethod(target.getClass(), method);
            return m == null ? null : m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String enchantmentName(Enchantment enchantment) {
        String key = null;
        Object namespaced = call(enchantment, "getKey"); // 1.13+
        if (namespaced != null) {
            Object k = call(namespaced, "getKey");
            if (k != null) {
                key = String.valueOf(k);
            }
        }
        if (key == null) {
            Object legacy = call(enchantment, "getName"); // 1.8 - 1.12
            key = legacy == null ? String.valueOf(enchantment) : String.valueOf(legacy);
        }
        key = key.toUpperCase(Locale.ROOT);
        return ENCHANTS.containsKey(key) ? ENCHANTS.get(key) : pretty(key);
    }

    private static boolean singleLevel(Enchantment enchantment, int level) {
        Object max = call(enchantment, "getMaxLevel");
        return max instanceof Number ? ((Number) max).intValue() <= 1 : false;
    }

    private static List<String> enchantments(ItemStack item) {
        Map<Enchantment, Integer> all = new HashMap<>();
        try {
            all.putAll(item.getEnchantments());
        } catch (Throwable ignored) {
            // fall through to meta below
        }
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta != null) {
            try {
                all.putAll(meta.getEnchants());
            } catch (Throwable ignored) {
                // not available
            }
        }
        if (meta instanceof EnchantmentStorageMeta) {
            all.putAll(((EnchantmentStorageMeta) meta).getStoredEnchants());
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> entry : all.entrySet()) {
            String name = enchantmentName(entry.getKey());
            int level = entry.getValue() == null ? 1 : entry.getValue();
            if (singleLevel(entry.getKey(), level)) {
                lines.add(name);
            } else {
                lines.add(name + " " + (level >= 1 && level < ROMAN.length ? ROMAN[level] : String.valueOf(level)));
            }
        }
        Collections.sort(lines);
        return lines;
    }

    /** Items stored inside a shulker box (or any container / bundle item), or an empty list. */
    private static List<ItemStack> containedItems(ItemStack item) {
        List<ItemStack> out = new ArrayList<>();
        try {
            ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
            if (meta instanceof BlockStateMeta) {
                BlockState state = ((BlockStateMeta) meta).getBlockState();
                if (state instanceof InventoryHolder) {
                    for (ItemStack inner : ((InventoryHolder) state).getInventory().getContents()) {
                        if (!isEmpty(inner)) {
                            out.add(inner);
                        }
                    }
                }
            } else if (meta != null && meta.getClass().getSimpleName().contains("Bundle")) { // 1.17+
                Object items = call(meta, "getItems");
                if (items instanceof Collection) {
                    for (Object inner : (Collection<?>) items) {
                        if (inner instanceof ItemStack && !isEmpty((ItemStack) inner)) {
                            out.add((ItemStack) inner);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // unsupported on this server version
        }
        return out;
    }

    /** Contents merged by identical item, e.g. "128x Cobblestone", "1x Diamond Sword (Sharpness V)". */
    private static List<String> contentLines(List<ItemStack> contents, String indent) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (ItemStack inner : contents) {
            String label = itemName(inner);
            List<String> enchantments = enchantments(inner);
            if (!enchantments.isEmpty()) {
                label += " (" + join(enchantments) + ")";
            }
            Integer count = merged.get(label);
            merged.put(label, (count == null ? 0 : count) + inner.getAmount());
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            lines.add(indent + entry.getValue() + "x " + entry.getKey());
        }
        return lines;
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(parts.get(i));
        }
        return out.toString();
    }

    private static int damage(ItemStack item) {
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta != null) {
            Object value = call(meta, "getDamage"); // 1.13+ Damageable
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        return item.getDurability();
    }

    /** Full tooltip-style lines for one item. */
    private static List<String> itemLines(ItemStack item, boolean withContents) {
        try {
            return itemLinesUnsafe(item, withContents);
        } catch (Throwable t) {
            List<String> lines = new ArrayList<>();
            lines.add(itemTag(item));
            lines.add("  Type: " + ItemNames.baseName(item));
            return lines;
        }
    }

    private static List<String> itemLinesUnsafe(ItemStack item, boolean withContents) {
        List<String> lines = new ArrayList<>();
        lines.add(itemTag(item));
        if (hasCustomName(item)) {
            lines.add("  Type: " + ItemNames.baseName(item));
        }
        for (String enchantment : enchantments(item)) {
            lines.add("  " + enchantment);
        }
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta != null && meta.hasLore() && meta.getLore() != null) {
            for (String lore : meta.getLore()) {
                String clean = safe(lore);
                if (!clean.trim().isEmpty()) {
                    lines.add("  " + clean);
                }
            }
        }
        int maxDurability = item.getType().getMaxDurability();
        int used = damage(item);
        if (maxDurability > 0 && used > 0) {
            lines.add("  Durability: " + Math.max(0, maxDurability - used) + "/" + maxDurability);
        }
        List<ItemStack> contents = withContents ? containedItems(item) : new ArrayList<ItemStack>();
        if (!contents.isEmpty()) {
            lines.add("  Contents:");
            lines.addAll(contentLines(contents, "    "));
        }
        return lines;
    }

    /** One-line description for inventory listings: "64x Cobblestone" or "1x Sword (Sharpness V)". */
    private static String shortLine(ItemStack item) {
        StringBuilder out = new StringBuilder();
        out.append(item.getAmount()).append("x ").append(itemName(item));
        try {
            List<String> enchantments = enchantments(item);
            if (!enchantments.isEmpty()) {
                out.append(" (").append(join(enchantments)).append(')');
            }
        } catch (Throwable ignored) {
            // show the plain name
        }
        return out.toString();
    }

    /** {@link #shortLine} followed by the contents of a shulker box / bundle, indented under it. */
    private static List<String> shortLines(String prefix, ItemStack item) {
        List<String> lines = new ArrayList<>();
        lines.add(prefix + shortLine(item));
        List<ItemStack> contents = containedItems(item);
        if (!contents.isEmpty()) {
            lines.addAll(contentLines(contents, indentFor(prefix) + "  - "));
        }
        return lines;
    }

    private static String indentFor(String prefix) {
        StringBuilder spaces = new StringBuilder();
        for (int i = 0; i < prefix.length() && prefix.charAt(i) == ' '; i++) {
            spaces.append(' ');
        }
        return spaces.toString() + "  ";
    }

    private static List<String> inventoryLines(Player player) {
        List<String> lines = new ArrayList<>();
        lines.add(safe(player.getName()) + "'s Inventory");
        PlayerInventory inventory = player.getInventory();

        List<String> armor = new ArrayList<>();
        ItemStack[] pieces = inventory.getArmorContents();
        for (int i = pieces.length - 1; i >= 0; i--) { // helmet first
            if (!isEmpty(pieces[i]) && i < ARMOR_LABELS.length) {
                armor.addAll(shortLines("  " + ARMOR_LABELS[i] + ": ", pieces[i]));
            }
        }
        List<String> hotbar = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isEmpty(item)) {
                hotbar.addAll(shortLines("  " + (slot + 1) + ": ", item));
            }
        }
        List<String> main = new ArrayList<>();
        for (int slot = 9; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isEmpty(item)) {
                main.addAll(shortLines("  " + (slot - 8) + ": ", item));
            }
        }
        addSection(lines, "Armor", armor);
        addSection(lines, "Hotbar", hotbar);
        addSection(lines, "Inventory", main);
        if (armor.isEmpty() && hotbar.isEmpty() && main.isEmpty()) {
            lines.add("  (empty)");
        }
        return lines;
    }

    private static List<String> enderLines(Player player) {
        List<String> lines = new ArrayList<>();
        lines.add(safe(player.getName()) + "'s Ender Chest");
        Inventory chest = player.getEnderChest();
        boolean any = false;
        for (int slot = 0; slot < chest.getSize(); slot++) {
            ItemStack item = chest.getItem(slot);
            if (!isEmpty(item)) {
                lines.addAll(shortLines("  " + (slot + 1) + ": ", item));
                any = true;
            }
        }
        if (!any) {
            lines.add("  (empty)");
        }
        return lines;
    }

    private static void addSection(List<String> lines, String title, List<String> entries) {
        if (!entries.isEmpty()) {
            lines.add(title);
            lines.addAll(entries);
        }
    }

    // ---- Fluxer markdown helpers ----

    /** A Fluxer code block, cut to {@code max} characters. */
    private static String codeBlock(int max, List<String> lines) {
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            if (body.length() + line.length() + 1 > max) {
                body.append("... (truncated)");
                break;
            }
            body.append(line).append('\n');
        }
        String text = body.toString();
        while (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return "```\n" + text + "\n```";
    }

    /** Makes untrusted text safe inside Fluxer inline code / code blocks. */
    private static String safe(String text) {
        if (text == null) {
            return "";
        }
        String clean = ChatColor.stripColor(text)
                .replace("\r", " ").replace("\n", " ").replace("`", "'");
        return defuse(clean);
    }

    // Defuse @everyone / @here so item names and lore can never ping.
    private static String defuse(String text) {
        return FluxerSRV.stripMassMentions(text);
    }
}
