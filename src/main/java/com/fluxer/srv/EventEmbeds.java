package com.fluxer.srv;

import org.bukkit.configuration.file.FileConfiguration;

/** Sends join/quit/advancement notices to Fluxer as embeds with configurable colors. */
public final class EventEmbeds {

    private EventEmbeds() {
    }

    /**
     * Sends {@code text} to the channel: as an embed colored from {@code embeds.colors.<colorKey>}
     * when {@code embeds.enabled} is true, otherwise as a plain message.
     */
    public static void send(FluxerSRV plugin, String channelId, String text, String colorKey, int defaultColor)
            throws java.io.IOException {
        FileConfiguration config = plugin.getConfig();
        if (config.getBoolean("embeds.enabled", true)) {
            int color = parseColor(config.get("embeds.colors." + colorKey), defaultColor);
            plugin.getFluxerClient().getRestApi().sendEmbed(channelId, text, color);
        } else {
            plugin.getFluxerClient().getRestApi().sendMessage(channelId, text);
        }
    }

    /** Accepts "#RRGGBB", "RRGGBB", "0xRRGGBB" or a plain decimal number; falls back to the default. */
    static int parseColor(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() & 0xFFFFFF;
        }
        String text = value.toString().trim();
        try {
            if (text.startsWith("#")) {
                return Integer.parseInt(text.substring(1), 16) & 0xFFFFFF;
            }
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return Integer.parseInt(text.substring(2), 16) & 0xFFFFFF;
            }
            if (text.matches("[0-9A-Fa-f]{6}") && text.matches(".*[A-Fa-f].*")) {
                return Integer.parseInt(text, 16);
            }
            return Integer.parseInt(text) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
