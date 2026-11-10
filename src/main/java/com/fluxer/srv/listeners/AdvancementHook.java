package com.fluxer.srv.listeners;

import com.fluxer.srv.EventEmbeds;
import com.fluxer.srv.AdvancementNames;
import com.fluxer.srv.FluxerSRV;
import org.bukkit.ChatColor;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;

/**
 * Sends player advancements to Fluxer.
 * <p>
 * The plugin is compiled against the 1.8 API, which has no advancement classes, so on 1.12+ servers
 * the event (and the advancement's title) is looked up by reflection instead of directly.
 */
public final class AdvancementHook {

    private static final String DEFAULT_ADVANCEMENT =
            "\uD83C\uDFC6 **%player_name%** has made the advancement **%advancement%**";
    private static final String DEFAULT_GOAL =
            "\uD83C\uDFAF **%player_name%** has reached the goal **%advancement%**";
    private static final String DEFAULT_CHALLENGE =
            "\uD83C\uDFC5 **%player_name%** has completed the challenge **%advancement%**";

    private AdvancementHook() {
    }

    /** Registers handling of PlayerAdvancementDoneEvent. Returns false on servers older than 1.12. */
    public static boolean register(FluxerSRV plugin) {
        Class<? extends Event> eventClass;
        try {
            eventClass = Class.forName("org.bukkit.event.player.PlayerAdvancementDoneEvent").asSubclass(Event.class);
        } catch (ClassNotFoundException e) {
            return false;
        }

        EventExecutor executor = (listener, event) -> {
            try {
                handle(plugin, event);
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not read advancement event: " + t);
            }
        };
        plugin.getServer().getPluginManager().registerEvent(eventClass, new Listener() {
        }, EventPriority.MONITOR, executor, plugin, true);
        return true;
    }

    private static void handle(FluxerSRV plugin, Event event) throws Exception {
        if (!(event instanceof PlayerEvent)) {
            return;
        }
        String playerName = ((PlayerEvent) event).getPlayer().getName();

        Object advancement = event.getClass().getMethod("getAdvancement").invoke(event);
        Class<?> advancementClass = Class.forName("org.bukkit.advancement.Advancement");

        String key = String.valueOf(advancementClass.getMethod("getKey").invoke(advancement));
        String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;

        String title = null;
        String description = "";
        String type = "TASK";

        Method getDisplay = null;
        try {
            getDisplay = advancementClass.getMethod("getDisplay");
        } catch (NoSuchMethodException ignored) {
            // older 1.12-1.19 API: no display info
        }

        if (getDisplay != null) {
            Object display = getDisplay.invoke(advancement);
            if (display == null) {
                return; // hidden advancements, e.g. recipe unlocks
            }
            Class<?> displayClass = Class.forName("org.bukkit.advancement.AdvancementDisplay");
            Object announce = call(displayClass, display, "shouldAnnounceChat");
            if (announce instanceof Boolean && !((Boolean) announce)) {
                return;
            }
            Object t = call(displayClass, display, "getTitle");
            if (t instanceof String) {
                title = (String) t;
            }
            Object d = call(displayClass, display, "getDescription");
            if (d instanceof String) {
                description = (String) d;
            }
            Object ty = call(displayClass, display, "getType");
            if (ty != null) {
                type = String.valueOf(ty);
            }
        } else if (path.startsWith("recipes/") || path.endsWith("/root")) {
            return;
        }

        AdvancementNames.Info known = AdvancementNames.lookup(path);
        if (known != null) {
            if (AdvancementNames.looksUntranslated(title)) {
                title = known.title;
            }
            if (getDisplay == null) {
                type = known.type; // old APIs don't expose the frame type
            }
        }
        if (title == null || title.trim().isEmpty()) {
            title = titleFromKey(path);
        }
        announce(plugin, playerName, title, description, type);
    }

    private static Object call(Class<?> type, Object target, String method) {
        try {
            return type.getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** "story/mine_stone" -> "Mine Stone" (used when the real title isn't available). */
    private static String titleFromKey(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : last.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /**
     * Posts an advancement to the global channel.
     *
     * @param type TASK, GOAL or CHALLENGE (anything else is treated as TASK)
     */
    public static void announce(FluxerSRV plugin, String playerName, String title, String description, String type) {
        if (!plugin.getConfig().getBoolean("announce-advancements", true)) {
            return;
        }
        String mainChannelId = plugin.getMainChannelId();
        if (mainChannelId == null || mainChannelId.isEmpty() || mainChannelId.equals("000000000000000000")) {
            return;
        }

        String configKey;
        String fallback;
        String colorKey;
        int defaultColor;
        switch (type == null ? "TASK" : type.toUpperCase()) {
            case "GOAL":
                configKey = "formats.goal";
                fallback = DEFAULT_GOAL;
                colorKey = "goal";
                defaultColor = 0x3498DB;
                break;
            case "CHALLENGE":
                configKey = "formats.challenge";
                fallback = DEFAULT_CHALLENGE;
                colorKey = "challenge";
                defaultColor = 0x9B59B6;
                break;
            default:
                configKey = "formats.advancement";
                fallback = DEFAULT_ADVANCEMENT;
                colorKey = "advancement";
                defaultColor = 0xFEE75C;
                break;
        }

        String message = plugin.getConfig().getString(configKey, fallback)
                .replace("%player_name%", playerName)
                .replace("%advancement%", ChatColor.stripColor(title))
                .replace("%description%", ChatColor.stripColor(description));

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                EventEmbeds.send(plugin, mainChannelId, message, colorKey, defaultColor);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send advancement to Fluxer: " + e.getMessage());
            }
        });
    }
}
