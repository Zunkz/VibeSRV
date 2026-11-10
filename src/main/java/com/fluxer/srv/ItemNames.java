package com.fluxer.srv;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Works out the name the game shows for an item ("Crafting Table", "Potion of Healing",
 * "Bottle o' Enchanting") rather than just prettifying the Material constant.
 * <p>
 * Order: the server's own translation (Paper) -> potion names -> known renames -> legacy
 * (1.8-1.12) data values -> Title Case of the Material name.
 */
public final class ItemNames {

    private static final Map<String, String> RENAMES = new HashMap<>();
    private static final Map<String, String> POTION_EFFECTS = new HashMap<>();
    private static final String[] COLORS = {"White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime", "Pink",
            "Gray", "Light Gray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black"};
    private static final String[] DYES = {"Ink Sac", "Rose Red", "Cactus Green", "Cocoa Beans", "Lapis Lazuli",
            "Purple Dye", "Cyan Dye", "Light Gray Dye", "Gray Dye", "Pink Dye", "Lime Dye", "Dandelion Yellow",
            "Light Blue Dye", "Magenta Dye", "Orange Dye", "Bone Meal"};
    private static final String[] WOODS = {"Oak", "Spruce", "Birch", "Jungle", "Acacia", "Dark Oak"};

    /** True on 1.13+, where FILLED_MAP exists and MAP means the empty map. */
    private static final boolean MODERN = org.bukkit.Material.getMaterial("FILLED_MAP") != null;

    private static void r(String material, String name) {
        RENAMES.put(material, name);
    }

    static {
        r("EXPERIENCE_BOTTLE", "Bottle o' Enchanting");
        r("EXP_BOTTLE", "Bottle o' Enchanting");
        r("ENDER_EYE", "Eye of Ender");
        r("EYE_OF_ENDER", "Eye of Ender");
        r("COMPARATOR", "Redstone Comparator");
        r("REDSTONE_COMPARATOR", "Redstone Comparator");
        r("REPEATER", "Redstone Repeater");
        r("DIODE", "Redstone Repeater");
        r("COOKED_BEEF", "Steak");
        r("BEEF", "Raw Beef");
        r("RAW_BEEF", "Raw Beef");
        r("MUTTON", "Raw Mutton");
        r("CHICKEN", "Raw Chicken");
        r("RAW_CHICKEN", "Raw Chicken");
        r("COD", "Raw Cod");
        r("SALMON", "Raw Salmon");
        r("RABBIT", "Raw Rabbit");
        r("PORKCHOP", "Raw Porkchop");
        r("PORK", "Raw Porkchop");
        r("GRILLED_PORK", "Cooked Porkchop");
        r("FILLED_MAP", "Map");
        r("EMPTY_MAP", "Empty Map");
        r("TNT", "TNT");
        r("TNT_MINECART", "Minecart with TNT");
        r("EXPLOSIVE_MINECART", "Minecart with TNT");
        r("CHEST_MINECART", "Minecart with Chest");
        r("STORAGE_MINECART", "Minecart with Chest");
        r("FURNACE_MINECART", "Minecart with Furnace");
        r("POWERED_MINECART", "Minecart with Furnace");
        r("HOPPER_MINECART", "Minecart with Hopper");
        r("COMMAND_BLOCK_MINECART", "Minecart with Command Block");
        r("COMMAND_MINECART", "Minecart with Command Block");
        r("JACK_O_LANTERN", "Jack o'Lantern");
        r("DISC_FRAGMENT_5", "Disc Fragment");
        r("DRAGON_BREATH", "Dragon's Breath");
        r("TURTLE_HELMET", "Turtle Shell");
        r("SPAWNER", "Monster Spawner");
        r("MOB_SPAWNER", "Monster Spawner");
        r("REDSTONE", "Redstone Dust");
        r("QUARTZ", "Nether Quartz");
        r("WRITABLE_BOOK", "Book and Quill");
        r("BOOK_AND_QUILL", "Book and Quill");
        r("NETHERITE_UPGRADE_SMITHING_TEMPLATE", "Netherite Upgrade");
        r("LEASH", "Lead");
        r("SNOW_BALL", "Snowball");
        r("CLAY_BRICK", "Brick");
        r("NETHER_BRICK_ITEM", "Nether Brick");
        r("NETHER_STALK", "Nether Wart");
        r("BREWING_STAND_ITEM", "Brewing Stand");
        r("CAULDRON_ITEM", "Cauldron");
        r("FLOWER_POT_ITEM", "Flower Pot");
        r("WORKBENCH", "Crafting Table");
        r("SULPHUR", "Gunpowder");
        r("ENDER_STONE", "End Stone");
        r("SMOOTH_BRICK", "Stone Bricks");
        r("MONSTER_EGG", "Spawn Egg");
        r("SPAWN_EGG", "Spawn Egg");
        r("RAW_FISH", "Raw Fish");
        r("COOKED_FISH", "Cooked Fish");
        r("GOLD_RECORD", "Music Disc");
        r("GREEN_RECORD", "Music Disc");
        r("RECORD_3", "Music Disc");
        r("RECORD_4", "Music Disc");
        r("RECORD_5", "Music Disc");
        r("RECORD_6", "Music Disc");
        r("RECORD_7", "Music Disc");
        r("RECORD_8", "Music Disc");
        r("RECORD_9", "Music Disc");
        r("RECORD_10", "Music Disc");
        r("RECORD_11", "Music Disc");
        r("RECORD_12", "Music Disc");
        r("SKULL_ITEM", "Mob Head");
        r("SKULL", "Mob Head");
        r("WOOD_BUTTON", "Wooden Button");
        r("WOOD_PLATE", "Wooden Pressure Plate");
        r("STONE_PLATE", "Stone Pressure Plate");
        r("IRON_PLATE", "Weighted Pressure Plate (Heavy)");
        r("GOLD_PLATE", "Weighted Pressure Plate (Light)");
        r("WOOD_DOOR", "Oak Door");
        r("WOOD_STEP", "Wooden Slab");
        r("WOOD_DOUBLE_STEP", "Wooden Slab");
        r("WOOD_STAIRS", "Oak Wood Stairs");
        r("WOOD_AXE", "Wooden Axe");
        r("WOOD_PICKAXE", "Wooden Pickaxe");
        r("WOOD_SWORD", "Wooden Sword");
        r("WOOD_SPADE", "Wooden Shovel");
        r("WOOD_HOE", "Wooden Hoe");
        r("GOLD_AXE", "Golden Axe");
        r("GOLD_PICKAXE", "Golden Pickaxe");
        r("GOLD_SWORD", "Golden Sword");
        r("GOLD_SPADE", "Golden Shovel");
        r("GOLD_HOE", "Golden Hoe");
        r("GOLD_HELMET", "Golden Helmet");
        r("GOLD_CHESTPLATE", "Golden Chestplate");
        r("GOLD_LEGGINGS", "Golden Leggings");
        r("GOLD_BOOTS", "Golden Boots");
        r("GOLD_BARDING", "Golden Horse Armor");
        r("IRON_BARDING", "Iron Horse Armor");
        r("DIAMOND_BARDING", "Diamond Horse Armor");
        r("STONE_SPADE", "Stone Shovel");
        r("IRON_SPADE", "Iron Shovel");
        r("DIAMOND_SPADE", "Diamond Shovel");
        r("NETHERITE_SHOVEL", "Netherite Shovel");
        r("SIGN", "Sign");
        r("SIGN_POST", "Sign");
        r("BED", "Bed");
        r("ENCHANTMENT_TABLE", "Enchantment Table");
        r("THIN_GLASS", "Glass Pane");
        r("IRON_FENCE", "Iron Bars");
        r("FENCE", "Oak Fence");
        r("FENCE_GATE", "Oak Fence Gate");
        r("LEAVES", "Leaves");
        r("LOG", "Oak Wood");
        r("WOOD", "Wooden Planks");
        r("SMOOTH_STAIRS", "Stone Brick Stairs");
        r("CLAY", "Clay");
        r("HARD_CLAY", "Terracotta");
        r("STAINED_CLAY", "Stained Clay");
        r("STAINED_GLASS", "Stained Glass");
        r("STAINED_GLASS_PANE", "Stained Glass Pane");
        r("CARPET", "Carpet");
        r("WOOL", "Wool");
        r("INK_SACK", "Ink Sac");
        r("COAL", "Coal");
        r("GOLDEN_APPLE", "Golden Apple");
        r("YELLOW_FLOWER", "Dandelion");
        r("RED_ROSE", "Poppy");
        r("MELON_BLOCK", "Melon");
        r("SPECKLED_MELON", "Glistering Melon Slice");
        r("POTATO_ITEM", "Potato");
        r("CARROT_ITEM", "Carrot");
        r("BAKED_POTATO", "Baked Potato");
        r("POISONOUS_POTATO", "Poisonous Potato");
        r("PISTON_BASE", "Piston");
        r("PISTON_STICKY_BASE", "Sticky Piston");
        r("REDSTONE_LAMP_OFF", "Redstone Lamp");
        r("REDSTONE_LAMP_ON", "Redstone Lamp");
        r("REDSTONE_TORCH_ON", "Redstone Torch");
        r("REDSTONE_TORCH_OFF", "Redstone Torch");
        r("WATER_LILY", "Lily Pad");
        r("ENDER_PORTAL_FRAME", "End Portal Frame");
        r("COMMAND", "Command Block");
        r("MYCEL", "Mycelium");
        r("DOUBLE_PLANT", "Large Flowers");
        r("LONG_GRASS", "Grass");
        r("DEAD_BUSH", "Dead Bush");
        r("SPONGE", "Sponge");
        r("GLOWSTONE_DUST", "Glowstone Dust");
        r("CARROT_STICK", "Carrot on a Stick");
        r("FLINT_AND_STEEL", "Flint and Steel");
        r("LEATHER_HELMET", "Leather Cap");
        r("LEATHER_CHESTPLATE", "Leather Tunic");
        r("LEATHER_LEGGINGS", "Leather Pants");
        r("LEATHER_BOOTS", "Leather Boots");
        r("ENDER_CHEST", "Ender Chest");
        r("ROTTEN_FLESH", "Rotten Flesh");

        // Modern (1.13 - 26.3) items whose in-game name differs from the Material constant.
        // Mob buckets
        r("COD_BUCKET", "Bucket of Cod");
        r("SALMON_BUCKET", "Bucket of Salmon");
        r("PUFFERFISH_BUCKET", "Bucket of Pufferfish");
        r("TROPICAL_FISH_BUCKET", "Bucket of Tropical Fish");
        r("AXOLOTL_BUCKET", "Bucket of Axolotl");
        r("TADPOLE_BUCKET", "Bucket of Tadpole");
        r("SULFUR_CUBE_BUCKET", "Bucket of Sulfur Cube"); // 26.2
        r("BUCKET_OF_SULFUR_CUBE", "Bucket of Sulfur Cube");
        // Storage blocks
        r("IRON_BLOCK", "Block of Iron");
        r("GOLD_BLOCK", "Block of Gold");
        r("DIAMOND_BLOCK", "Block of Diamond");
        r("EMERALD_BLOCK", "Block of Emerald");
        r("REDSTONE_BLOCK", "Block of Redstone");
        r("LAPIS_BLOCK", "Block of Lapis Lazuli");
        r("COAL_BLOCK", "Block of Coal");
        r("NETHERITE_BLOCK", "Block of Netherite");
        r("COPPER_BLOCK", "Block of Copper");
        r("WAXED_COPPER_BLOCK", "Waxed Block of Copper");
        r("AMETHYST_BLOCK", "Block of Amethyst");
        r("QUARTZ_BLOCK", "Block of Quartz");
        r("RAW_IRON_BLOCK", "Block of Raw Iron");
        r("RAW_COPPER_BLOCK", "Block of Raw Copper");
        r("RAW_GOLD_BLOCK", "Block of Raw Gold");
        r("RESIN_BLOCK", "Block of Resin");
        // Other
        r("HAY_BLOCK", "Hay Bale");
        r("SMOOTH_QUARTZ", "Smooth Quartz Block");
        r("JIGSAW", "Jigsaw Block");

        String[][] effects = {
                {"SPEED", "Swiftness"}, {"SWIFTNESS", "Swiftness"}, {"SLOWNESS", "Slowness"},
                {"JUMP", "Leaping"}, {"LEAPING", "Leaping"}, {"STRENGTH", "Strength"},
                {"INSTANT_HEAL", "Healing"}, {"HEALING", "Healing"}, {"INSTANT_DAMAGE", "Harming"},
                {"HARMING", "Harming"}, {"REGEN", "Regeneration"}, {"REGENERATION", "Regeneration"},
                {"FIRE_RESISTANCE", "Fire Resistance"}, {"WATER_BREATHING", "Water Breathing"},
                {"INVISIBILITY", "Invisibility"}, {"NIGHT_VISION", "Night Vision"}, {"WEAKNESS", "Weakness"},
                {"POISON", "Poison"}, {"LUCK", "Luck"}, {"SLOW_FALLING", "Slow Falling"},
                {"TURTLE_MASTER", "the Turtle Master"}, {"WIND_CHARGED", "Wind Charging"},
                {"WEAVING", "Weaving"}, {"OOZING", "Oozing"}, {"INFESTED", "Infestation"}
        };
        for (String[] pair : effects) {
            POTION_EFFECTS.put(pair[0], pair[1]);
        }
    }

    private ItemNames() {
    }

    /** The name shown for the item type, ignoring any custom (anvil-renamed) name. */
    public static String baseName(ItemStack item) {
        String material = item.getType().name();

        // 1. Paper and forks can translate item names with the server's own language file.
        String translated = serverTranslated(item);
        if (translated != null) {
            return translated;
        }

        // 2. Potions, splash/lingering potions and tipped arrows.
        String potion = potionName(item, material);
        if (potion != null) {
            return potion;
        }

        // 3. 1.8-1.12 items whose variant is stored in the data value.
        String legacy = legacyVariant(material, item.getDurability());
        if (legacy != null) {
            return legacy;
        }

        // Names whose meaning changed in 1.13 (the Material constant is reused for a different item).
        if (!MODERN) {
            if (material.equals("MAP")) {
                return "Map";
            }
            if (material.equals("MELON")) {
                return "Melon Slice";
            }
            if (material.equals("BRICK")) {
                return "Bricks";
            }
        } else if (material.equals("MAP")) {
            return "Empty Map";
        }

        // 4. Renames, music discs, templates and banner patterns.
        String renamed = RENAMES.get(material);
        if (renamed != null) {
            return renamed;
        }
        if (material.startsWith("MUSIC_DISC_")) {
            return "Music Disc";
        }
        if (material.endsWith("_BANNER_PATTERN")) {
            return "Banner Pattern";
        }
        if (material.endsWith("_ARMOR_TRIM_SMITHING_TEMPLATE")) {
            return titleCase(material.substring(0, material.length() - "_SMITHING_TEMPLATE".length()));
        }
        if (material.endsWith("_SMITHING_TEMPLATE")) {
            return titleCase(material.substring(0, material.length() - "_SMITHING_TEMPLATE".length()));
        }
        return titleCase(material);
    }

    /** Title Case of an enum-style name, keeping small words lower-case: "TOTEM_OF_UNDYING" -> "Totem of Undying". */
    static String titleCase(String name) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String part : name.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!first) {
                out.append(' ');
            }
            boolean small = !first && (part.equals("of") || part.equals("the") || part.equals("on")
                    || part.equals("a") || part.equals("and") || part.equals("with"));
            out.append(small ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
            first = false;
        }
        return out.toString();
    }

    // ---- server translation (Paper) ----

    private static String serverTranslated(ItemStack item) {
        // Paper (<= 1.20): ItemStack#getI18NDisplayName() -> English name from the server's language file
        try {
            Method method = item.getClass().getMethod("getI18NDisplayName");
            Object value = method.invoke(item);
            String clean = cleanTranslation(value);
            if (clean != null) {
                return clean;
            }
        } catch (Throwable ignored) {
            // not Paper, or removed in this version
        }
        // Paper (newer): effectiveName() component, rendered through Adventure's global translator
        try {
            Object component = item.getClass().getMethod("effectiveName").invoke(item);
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Class<?> translator = Class.forName("net.kyori.adventure.translation.GlobalTranslator");
            Object rendered = translator.getMethod("render", componentClass, Locale.class)
                    .invoke(null, component, Locale.US);
            Class<?> serializer = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
            Object plain = serializer.getMethod("plainText").invoke(null);
            Object text = serializer.getMethod("serialize", componentClass).invoke(plain, rendered);
            String clean = cleanTranslation(text);
            if (clean != null) {
                return clean;
            }
        } catch (Throwable ignored) {
            // fall through to the built-in names
        }
        return null;
    }

    /** Accepts a translated name; rejects empty text and untranslated keys like "item.minecraft.stone". */
    static String cleanTranslation(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String text = ChatColor.stripColor((String) value).trim();
        if (text.length() > 2 && text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isEmpty() || text.matches("[a-z0-9_]+(\\.[a-z0-9_/]+)+")) {
            return null;
        }
        return text;
    }

    // ---- potions ----

    private static String potionName(ItemStack item, String material) {
        String prefix;
        boolean arrow = false;
        switch (material) {
            case "POTION":
                prefix = "";
                break;
            case "SPLASH_POTION":
                prefix = "Splash ";
                break;
            case "LINGERING_POTION":
                prefix = "Lingering ";
                break;
            case "TIPPED_ARROW":
                prefix = "";
                arrow = true;
                break;
            default:
                return null;
        }
        String type = potionType(item);
        if (type == null) {
            return null;
        }
        return potionLabel(prefix, arrow, type);
    }

    static String potionLabel(String prefix, boolean arrow, String type) {
        type = type.toUpperCase(Locale.ROOT);
        if (type.startsWith("LONG_")) {
            type = type.substring(5);
        } else if (type.startsWith("STRONG_")) {
            type = type.substring(7);
        }
        if (arrow) {
            String effect = POTION_EFFECTS.get(type);
            return effect != null ? "Arrow of " + effect : "Tipped Arrow";
        }
        switch (type) {
            case "WATER":
                return prefix.isEmpty() ? "Water Bottle" : prefix + "Water Bottle";
            case "AWKWARD":
            case "MUNDANE":
            case "THICK":
                return prefix + titleCase(type) + " Potion";
            case "UNCRAFTABLE":
            case "EMPTY":
                return prefix + "Uncraftable Potion";
            default:
                String effect = POTION_EFFECTS.get(type);
                if (effect == null) {
                    effect = titleCase(type);
                }
                return prefix + "Potion of " + effect;
        }
    }

    /** The potion's base type name across API generations, or null if it can't be read. */
    private static String potionType(ItemStack item) {
        ItemMeta meta;
        try {
            meta = item.hasItemMeta() ? item.getItemMeta() : null;
        } catch (Throwable t) {
            return null;
        }
        if (meta != null) {
            try { // 1.20.5+
                Object type = meta.getClass().getMethod("getBasePotionType").invoke(meta);
                if (type != null) {
                    return String.valueOf(type instanceof Enum ? ((Enum<?>) type).name() : type)
                            .replace("minecraft:", "").toUpperCase(Locale.ROOT);
                }
            } catch (Throwable ignored) {
                // older API
            }
            try { // 1.9 - 1.20.4
                Object data = meta.getClass().getMethod("getBasePotionData").invoke(meta);
                Object type = data.getClass().getMethod("getType").invoke(data);
                if (type != null) {
                    return String.valueOf(type);
                }
            } catch (Throwable ignored) {
                // 1.8
            }
        }
        try { // 1.8: the potion kind is stored in the data value
            org.bukkit.potion.Potion potion = org.bukkit.potion.Potion.fromItemStack(item);
            if (potion != null && potion.getType() != null) {
                return potion.getType().name();
            }
        } catch (Throwable ignored) {
            // unsupported on this server
        }
        return null;
    }

    // ---- legacy data values (1.8 - 1.12) ----

    private static String legacyVariant(String material, short data) {
        if (data <= 0) {
            return null;
        }
        switch (material) {
            case "WOOL":
                return color(data) == null ? null : color(data) + " Wool";
            case "CARPET":
                return color(data) == null ? null : color(data) + " Carpet";
            case "STAINED_GLASS":
                return color(data) == null ? null : color(data) + " Stained Glass";
            case "STAINED_GLASS_PANE":
                return color(data) == null ? null : color(data) + " Stained Glass Pane";
            case "STAINED_CLAY":
                return color(data) == null ? null : color(data) + " Terracotta";
            case "BANNER":
                return color(15 - data) == null ? null : color(15 - data) + " Banner";
            case "INK_SACK":
                return data < DYES.length ? DYES[data] : null;
            case "COAL":
                return data == 1 ? "Charcoal" : null;
            case "GOLDEN_APPLE":
                return data == 1 ? "Enchanted Golden Apple" : null;
            case "LOG":
                return data % 4 < 4 ? woodName(data % 4) + " Wood" : null;
            case "LOG_2":
                return woodName(4 + data % 2) + " Wood";
            case "WOOD":
                return data < WOODS.length ? WOODS[data] + " Wood Planks" : null;
            case "SAPLING":
                return data < WOODS.length ? WOODS[data] + " Sapling" : null;
            case "SKULL_ITEM":
                String[] heads = {"Skeleton Skull", "Wither Skeleton Skull", "Zombie Head", "Head", "Creeper Head"};
                return data < heads.length ? heads[data] : null;
            default:
                return null;
        }
    }

    private static String color(int index) {
        return index >= 0 && index < COLORS.length ? COLORS[index] : null;
    }

    private static String woodName(int index) {
        return WOODS[Math.min(Math.max(index, 0), WOODS.length - 1)];
    }
}
