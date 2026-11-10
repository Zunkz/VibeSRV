package com.fluxer.srv;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
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

        Result(String message, String details) {
            this.message = message;
            this.details = details;
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
        int max = maxLength();

        if (sectionEnabled("item") && contains(out, keyword("item", "[item]"))
                && allowed(player, "interactivechat.module.item", null)) {
            ItemStack held = player.getItemInHand();
            String inline;
            if (isEmpty(held)) {
                inline = cfg().getString("interactivechat.item.empty-inline", "`[Nothing]`");
            } else {
                inline = cfg().getString("interactivechat.item.inline", "`[%item%]`")
                        .replace("%item%", itemTag(held))
                        .replace("%player%", safe(player.getName()));
                blocks.add(codeBlock(max, itemLines(held)));
            }
            out = replaceIgnoreCase(out, keyword("item", "[item]"), inline);
        }

        if (sectionEnabled("inventory") && contains(out, keyword("inventory", "[inv]"))
                && allowed(player, "interactivechat.module.inventory", null)) {
            String inline = cfg().getString("interactivechat.inventory.inline", "`[%player%'s Inventory]`")
                    .replace("%player%", safe(player.getName()));
            out = replaceIgnoreCase(out, keyword("inventory", "[inv]"), inline);
            blocks.add(codeBlock(max, inventoryLines(player)));
        }

        if (sectionEnabled("enderchest") && contains(out, keyword("enderchest", "[ender]"))
                && allowed(player, "interactivechat.module.enderchest", null)) {
            String inline = cfg().getString("interactivechat.enderchest.inline", "`[%player%'s Ender Chest]`")
                    .replace("%player%", safe(player.getName()));
            out = replaceIgnoreCase(out, keyword("enderchest", "[ender]"), inline);
            blocks.add(codeBlock(max, enderLines(player)));
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
        return new Result(out, details.toString());
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

    // ---- Fluxer text commands (!item, !inv, !ender, !playerlist) ----

    /**
     * Handles a message typed in the Fluxer global channel as a command. Returns true if it was
     * one (so it shouldn't be shown in-game). Off unless interactivechat.commands.enabled.
     */
    public boolean handleFluxerCommand(String channelId, String content) {
        if (!isEnabled() || !cfg().getBoolean("interactivechat.commands.enabled", false)) {
            return false;
        }
        String prefix = cfg().getString("interactivechat.commands.prefix", "!");
        String trimmed = content.trim();
        if (prefix == null || prefix.isEmpty() || !trimmed.startsWith(prefix)) {
            return false;
        }
        String[] parts = trimmed.substring(prefix.length()).trim().split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);
        if (!command.equals("playerlist") && !command.equals("item")
                && !command.equals("inv") && !command.equals("ender")) {
            return false;
        }
        String argument = parts.length > 1 ? parts[1] : "";

        plugin.getServer().getScheduler().runTask(plugin, () -> {
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

    private String commandReply(String prefix, String command, String argument) {
        int max = maxLength();
        if (command.equals("playerlist")) {
            Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();
            List<String> names = new ArrayList<>();
            for (Player player : online) {
                names.add(safe(player.getName()));
            }
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            List<String> lines = new ArrayList<>();
            lines.add("Players online (" + names.size() + "/" + plugin.getServer().getMaxPlayers() + ")");
            if (names.isEmpty()) {
                lines.add("  (nobody)");
            }
            for (String name : names) {
                lines.add("  " + name);
            }
            return codeBlock(max, lines);
        }

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
            return codeBlock(max, itemLines(held));
        }
        if (command.equals("inv")) {
            return codeBlock(max, inventoryLines(target));
        }
        return codeBlock(max, enderLines(target));
    }

    // ---- placeholder expansion ----

    private String expand(Player player, String text) {
        String s = text
                .replace("%player_name%", player.getName())
                .replace("%player_displayname%", ChatColor.stripColor(player.getDisplayName()))
                .replace("%player_world%", player.getWorld().getName())
                .replace("%player_x%", String.valueOf(player.getLocation().getBlockX()))
                .replace("%player_y%", String.valueOf(player.getLocation().getBlockY()))
                .replace("%player_z%", String.valueOf(player.getLocation().getBlockZ()));
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Method method = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
                s = (String) method.invoke(null, player, s);
            } catch (Throwable ignored) {
                // PlaceholderAPI missing or incompatible: leave %placeholders% as they are
            }
        }
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
        return pretty(item.getType().name());
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
    private static List<String> itemLines(ItemStack item) {
        try {
            return itemLinesUnsafe(item);
        } catch (Throwable t) {
            List<String> lines = new ArrayList<>();
            lines.add(itemTag(item));
            lines.add("  Type: " + pretty(item.getType().name()));
            return lines;
        }
    }

    private static List<String> itemLinesUnsafe(ItemStack item) {
        List<String> lines = new ArrayList<>();
        lines.add(itemTag(item));
        if (hasCustomName(item)) {
            lines.add("  Type: " + pretty(item.getType().name()));
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
        List<ItemStack> contents = containedItems(item);
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
        return text.replace("@everyone", "@\u200beveryone").replace("@here", "@\u200bhere");
    }
}
