package com.fluxer.srv;

import java.util.HashMap;
import java.util.Map;

/**
 * English titles and frame types of the vanilla advancements (all 121 of Java Edition 26.3), keyed by their path
 * ("story/mine_stone"). Servers between 1.12 and 1.20.1 don't expose an advancement's display info,
 * so this is what turns "story/mine_stone" into the in-game name "Stone Age".
 */
public final class AdvancementNames {

    public static final class Info {
        public final String title;
        public final String type; // TASK, GOAL or CHALLENGE

        Info(String title, String type) {
            this.title = title;
            this.type = type;
        }
    }

    private static final Map<String, Info> ADVANCEMENTS = new HashMap<>();

    private static void a(String path, String title) {
        ADVANCEMENTS.put(path, new Info(title, "TASK"));
    }

    private static void g(String path, String title) {
        ADVANCEMENTS.put(path, new Info(title, "GOAL"));
    }

    private static void c(String path, String title) {
        ADVANCEMENTS.put(path, new Info(title, "CHALLENGE"));
    }

    static {
        // Minecraft (story)
        a("story/mine_stone", "Stone Age");
        a("story/upgrade_tools", "Getting an Upgrade");
        a("story/smelt_iron", "Acquire Hardware");
        a("story/obtain_armor", "Suit Up");
        a("story/lava_bucket", "Hot Stuff");
        a("story/iron_tools", "Isn't It Iron Pick?"); // "?" added in 26.3
        a("story/deflect_arrow", "Not Today, Thank You");
        a("story/form_obsidian", "Ice Bucket Challenge");
        a("story/mine_diamond", "Diamonds!");
        a("story/enter_the_nether", "We Need to Go Deeper");
        a("story/shiny_gear", "Cover Me with Diamonds");
        a("story/enchant_item", "Enchanter");
        g("story/cure_zombie_villager", "Zombie Doctor");
        a("story/follow_ender_eye", "Eye Spy");
        a("story/enter_the_end", "The End?");

        // Nether
        c("nether/return_to_sender", "Return to Sender");
        a("nether/find_bastion", "Those Were the Days");
        a("nether/obtain_ancient_debris", "Hidden in the Depths");
        c("nether/fast_travel", "Subspace Bubble");
        a("nether/find_fortress", "A Terrible Fortress");
        a("nether/obtain_crying_obsidian", "Who is Cutting Onions?");
        a("nether/distract_piglin", "Oh Shiny");
        a("nether/ride_strider", "This Boat Has Legs");
        c("nether/uneasy_alliance", "Uneasy Alliance");
        a("nether/loot_bastion", "War Pigs");
        a("nether/use_lodestone", "Country Lode, Take Me Home");
        c("nether/netherite_armor", "Cover Me in Debris");
        a("nether/get_wither_skull", "Spooky Scary Skeleton");
        a("nether/obtain_blaze_rod", "Into Fire");
        a("nether/charge_respawn_anchor", "Not Quite \"Nine\" Lives");
        c("nether/explore_nether", "Hot Tourist Destinations");
        a("nether/summon_wither", "Withering Heights");
        a("nether/brew_potion", "Local Brewery");
        a("nether/create_beacon", "Bring Home the Beacon");
        c("nether/all_potions", "A Furious Cocktail");
        g("nether/create_full_beacon", "Beaconator");
        c("nether/all_effects", "How Did We Get Here?");
        a("nether/ride_strider_in_overworld_lava", "Feels Like Home");

        // The End
        a("end/kill_dragon", "Free the End");
        g("end/dragon_egg", "The Next Generation");
        a("end/enter_end_gateway", "Remote Getaway");
        g("end/respawn_dragon", "The End... Again...");
        g("end/dragon_breath", "You Need a Mint");
        a("end/find_end_city", "The City at the End of the Game");
        g("end/elytra", "Sky's the Limit");
        c("end/levitate", "Great View From Up Here");

        // Adventure
        a("adventure/voluntary_exile", "Voluntary Exile");
        a("adventure/spyglass_at_parrot", "Is It a Bird?");
        a("adventure/kill_a_mob", "Monster Hunter");
        a("adventure/trade", "What a Deal!");
        a("adventure/honey_block_slide", "Sticky Situation");
        a("adventure/ol_betsy", "Ol' Betsy");
        a("adventure/sleep_in_bed", "Sweet Dreams");
        c("adventure/hero_of_the_village", "Hero of the Village");
        a("adventure/spyglass_at_ghast", "Is It a Balloon?");
        a("adventure/throw_trident", "A Throwaway Joke");
        a("adventure/shoot_arrow", "Take Aim");
        c("adventure/kill_all_mobs", "Monsters Hunted");
        g("adventure/totem_of_undying", "Postmortal");
        g("adventure/summon_iron_golem", "Hired Help");
        c("adventure/two_birds_one_arrow", "Two Birds, One Arrow");
        a("adventure/whos_the_pillager_now", "Who's the Pillager Now?");
        c("adventure/arbalistic", "Arbalistic");
        c("adventure/adventuring_time", "Adventuring Time");
        a("adventure/play_jukebox_in_meadows", "Sound of Music");
        a("adventure/walk_on_powder_snow_with_leather_boots", "Light as a Rabbit");
        a("adventure/spyglass_at_dragon", "Is It a Plane?");
        a("adventure/very_very_frightening", "Very Very Frightening");
        c("adventure/sniper_duel", "Sniper Duel");
        c("adventure/bullseye", "Bullseye");
        a("adventure/fall_from_world_height", "Caves & Cliffs");
        a("adventure/avoid_vibration", "Sneak 100");
        c("adventure/kill_mob_near_sculk_catalyst", "It Spreads");
        a("adventure/lightning_rod_with_villager_no_fire", "Surge Protector");
        a("adventure/trade_at_world_height", "Star Trader");
        a("adventure/salvage_sherd", "Respecting the Remnants");
        a("adventure/craft_decorated_pot_using_only_sherds", "Careful Restoration");
        a("adventure/read_power_of_chiseled_bookshelf", "The Power of Books");
        a("adventure/trim_with_any_armor_pattern", "Crafting a New Look");
        c("adventure/trim_with_all_exclusive_armor_patterns", "Smithing with Style");
        a("adventure/under_lock_and_key", "Under Lock and Key");
        g("adventure/revaulting", "Revaulting");
        c("adventure/blowback", "Blowback");
        a("adventure/who_needs_rockets", "Who Needs Rockets?");
        a("adventure/crafters_crafting_crafters", "Crafters Crafting Crafters");
        a("adventure/lighten_up", "Lighten Up");
        c("adventure/overoverkill", "Over-Overkill");
        a("adventure/minecraft_trials_edition", "Minecraft: Trial(s) Edition");
        a("adventure/brush_armadillo", "Isn't It Scute?"); // 1.20.5
        a("adventure/heart_transplanter", "Heart Transplanter"); // 1.21.2
        g("adventure/spear_many_mobs", "Mob Kabob"); // 1.21.11

        // Husbandry
        a("husbandry/safely_harvest_honey", "Bee Our Guest");
        a("husbandry/breed_an_animal", "The Parrots and the Bats");
        a("husbandry/tame_an_animal", "Best Friends Forever");
        a("husbandry/fishy_business", "Fishy Business");
        a("husbandry/silk_touch_nest", "Total Beelocation");
        a("husbandry/plant_seed", "A Seedy Place");
        c("husbandry/bred_all_animals", "Two by Two");
        c("husbandry/complete_catalogue", "A Complete Catalogue");
        a("husbandry/tactical_fishing", "Tactical Fishing");
        c("husbandry/balanced_diet", "A Balanced Diet");
        c("husbandry/obtain_netherite_hoe", "Serious Dedication");
        a("husbandry/make_a_sign_glow", "Glow and Behold!");
        a("husbandry/ride_a_boat_with_a_goat", "Whatever Floats Your Goat!");
        a("husbandry/kill_axolotl_target", "The Healing Power of Friendship!");
        a("husbandry/axolotl_in_a_bucket", "The Cutest Predator");
        a("husbandry/tadpole_in_a_bucket", "Bukkit Bukkit");
        a("husbandry/leash_all_frog_variants", "When the Squad Hops into Town");
        c("husbandry/froglights", "With Our Powers Combined!");
        a("husbandry/allay_deliver_item_to_player", "You've Got a Friend in Me");
        a("husbandry/allay_deliver_cake_to_note_block", "Birthday Song");
        a("husbandry/feed_snifflet", "Little Sniffs");
        a("husbandry/obtain_sniffer_egg", "Smells Interesting");
        a("husbandry/plant_any_sniffer_seed", "Planting the Past");
        a("husbandry/repair_wolf_armor", "Good as New");
        c("husbandry/whole_pack", "The Whole Pack");
        a("husbandry/wax_on", "Wax On");
        a("husbandry/wax_off", "Wax Off");
        a("husbandry/place_dried_ghast_in_water", "Stay Hydrated!"); // 1.21.6
        a("husbandry/remove_wolf_armor", "Shear Brilliance"); // 1.21.6
        a("husbandry/uh_oh", "Uh Oh"); // 26.2
    }

    private AdvancementNames() {
    }

    /** The vanilla advancement for a path like "story/mine_stone", or null if it isn't a known one. */
    public static Info lookup(String path) {
        return path == null ? null : ADVANCEMENTS.get(path);
    }

    /** True for titles that are really translation keys / JSON rather than readable text. */
    public static boolean looksUntranslated(String title) {
        if (title == null) {
            return true;
        }
        String t = title.trim();
        return t.isEmpty() || t.startsWith("advancements.") || t.startsWith("{") || t.startsWith("translation.");
    }

    // Pre-1.12 achievements (PlayerAchievementAwardedEvent), by Bukkit's Achievement enum name.
    private static final Map<String, String> ACHIEVEMENTS = new HashMap<>();

    static {
        String[][] pairs = {
                {"OPEN_INVENTORY", "Taking Inventory"}, {"MINE_WOOD", "Getting Wood"},
                {"BUILD_WORKBENCH", "Benchmarking"}, {"BUILD_PICKAXE", "Time to Mine!"},
                {"BUILD_FURNACE", "Hot Topic"}, {"ACQUIRE_IRON", "Acquire Hardware"},
                {"BUILD_HOE", "Time to Farm!"}, {"MAKE_BREAD", "Bake Bread"},
                {"BAKE_CAKE", "The Lie"}, {"BUILD_BETTER_PICKAXE", "Getting an Upgrade"},
                {"COOK_FISH", "Delicious Fish"}, {"ON_A_RAIL", "On A Rail"},
                {"BUILD_SWORD", "Time to Strike!"}, {"KILL_ENEMY", "Monster Hunter"},
                {"KILL_COW", "Cow Tipper"}, {"FLY_PIG", "When Pigs Fly"},
                {"SNIPE_SKELETON", "Sniper Duel"}, {"GET_DIAMONDS", "DIAMONDS!"},
                {"NETHER_PORTAL", "We Need to Go Deeper"}, {"GHAST_RETURN", "Return to Sender"},
                {"GET_BLAZE_ROD", "Into Fire"}, {"BREW_POTION", "Local Brewery"},
                {"END_PORTAL", "The End?"}, {"THE_END", "The End."},
                {"ENCHANTMENTS", "Enchanter"}, {"OVERKILL", "Overkill"},
                {"BOOKCASE", "Librarian"}, {"EXPLORE_ALL_BIOMES", "Adventuring Time"},
                {"SPAWN_WITHER", "The Beginning?"}, {"KILL_WITHER", "The Beginning."},
                {"FULL_BEACON", "Beaconator"}, {"BREED_COW", "Repopulation"},
                {"DIAMONDS_TO_YOU", "Diamonds to you!"}, {"OVERPOWERED", "Overpowered"}
        };
        for (String[] pair : pairs) {
            ACHIEVEMENTS.put(pair[0], pair[1]);
        }
    }

    /** In-game name of a 1.8-1.11 achievement, or null if unknown. */
    public static String achievementTitle(String enumName) {
        return enumName == null ? null : ACHIEVEMENTS.get(enumName);
    }
}
