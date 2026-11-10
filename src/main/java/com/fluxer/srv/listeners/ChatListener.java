package com.fluxer.srv.listeners;

import com.fluxer.srv.ChatPlaceholders;
import com.fluxer.srv.FluxerSRV;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ChatListener implements Listener {

    private final FluxerSRV plugin;

    // What the player actually typed, captured before InteractiveChat rewrites it.
    private final Map<UUID, String> originals = new ConcurrentHashMap<>();

    // InteractiveChat's internal marker, e.g. <chat=f2f74812-762f-41fc-958b-adca0147b62c:...>
    private static final Pattern IC_MARKER = Pattern.compile("<chat=[0-9a-fA-F-]{36}:[^>]*>?");

    public ChatListener(FluxerSRV plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChatOriginal(AsyncPlayerChatEvent event) {
        originals.put(event.getPlayer().getUniqueId(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String original = originals.remove(event.getPlayer().getUniqueId());
        String mainChannelId = plugin.getMainChannelId();
        if (mainChannelId == null || mainChannelId.isEmpty() || mainChannelId.equals("000000000000000000")) {
            return;
        }

        String rawMessage = event.getMessage();
        if (rawMessage.contains("<chat=")) {
            // InteractiveChat already swapped [item]/[inv]/[ender] for its own marker. Use what the
            // player typed so the placeholders are rendered by us; otherwise just drop the marker.
            rawMessage = original != null && !original.contains("<chat=")
                    ? original
                    : IC_MARKER.matcher(rawMessage).replaceAll(plugin.getConfig()
                            .getString("interactivechat.item.keyword", "[item]")).trim();
        }
        if (plugin.getConfig().getBoolean("fluxer-global-only", false)) {
            if (!rawMessage.startsWith("!")) {
                return; // Local chat, DO NOT SEND to Fluxer
            }
            rawMessage = rawMessage.substring(1).trim(); // Remove '!' for Discord
        }

        // InteractiveChat-style placeholders ([item], [inv], [ender], custom): sent as Fluxer markdown
        String details = "";
        java.util.List<ChatPlaceholders.Embed> embeds = null;
        ChatPlaceholders placeholders = plugin.getChatPlaceholders();
        if (placeholders != null && placeholders.containsKeyword(rawMessage)) {
            ChatPlaceholders.Result result = placeholders.processSafely(event.getPlayer(), rawMessage);
            if (result != null) {
                rawMessage = result.message;
                details = result.details;
                embeds = result.embeds;
            }
        }
        if (!details.isEmpty()) {
            rawMessage = rawMessage + "\n" + details;
        }

        final String chatText = rawMessage;
        final String playerName = event.getPlayer().getName();
        final String playerUuid = event.getPlayer().getUniqueId().toString();
        final java.util.List<ChatPlaceholders.Embed> chatEmbeds = embeds;

        String format = plugin.getConfig().getString("formats.chat", "**%player_name%**: %message%");
        // If the message was only [item]/[inv]/[ender], the embed's title already names the player
        final String message = rawMessage.trim().isEmpty() && embeds != null && !embeds.isEmpty()
                ? ""
                : format.replace("%player_name%", event.getPlayer().getName())
                        .replace("%message%", rawMessage);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.sendPlayerChat(playerName, playerUuid, chatText, message, chatEmbeds));
    }
}
