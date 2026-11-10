package com.fluxer.srv.listeners;

import com.fluxer.srv.FluxerListener;
import com.fluxer.srv.FluxerSRV;
import com.fluxer.srv.FluxerText;
import com.google.gson.JsonObject;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FluxerMessageListener implements FluxerListener {

    private static final String DEFAULT_FORMAT = "&9[Fluxer] &f%username% &7%message%";
    private static final String DEFAULT_REPLY_FORMAT =
            "&9[Fluxer] &f%username% &8(replying to &7%reply_username%&8: &7%reply_message%&8) &7%message%";

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "%(username|display_name|nickname|reply_username|reply_display_name|reply_nickname|reply_message|message)%");

    private final FluxerSRV plugin;

    public FluxerMessageListener(FluxerSRV plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onMessageCreate(JsonObject message) {
        if (!message.has("author") || !message.has("channel_id") || !message.has("content")) {
            return;
        }

        // Ignore echoes of our own per-player chat webhook
        if (message.has("webhook_id") && !message.get("webhook_id").isJsonNull()
                && plugin.isOwnWebhook(message.get("webhook_id").getAsString())) {
            return;
        }

        JsonObject author = message.getAsJsonObject("author");
        if (FluxerText.bool(author, "bot")) {
            return; // Ignore bot messages
        }

        String channelId = message.get("channel_id").getAsString();
        String content = FluxerText.str(message, "content");
        if (content == null) {
            return;
        }
        String username = FluxerText.username(author);

        if (channelId.equals(plugin.getMainChannelId())) {
            // !item / !inv / !ender / !playerlist (off by default): answered in Fluxer, not shown in-game
            if (plugin.getChatPlaceholders().handleFluxerCommand(channelId, content)) {
                return;
            }
            List<String> lines = buildInGameLines(message, author, content);

            // Broadcast to the server
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String carry = "";
                for (String line : lines) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    String out = carry + line;
                    FluxerText.broadcast(plugin, out);
                    carry = ChatColor.getLastColors(out);
                }
            });

        } else if (channelId.equals(plugin.getConsoleChannelId())) {
            // Execute command
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getLogger().info("Executing console command from Fluxer (" + username + "): " + content);
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), content);
            });
        }
    }

    private List<String> buildInGameLines(JsonObject message, JsonObject author, String content) {
        String guildId = FluxerText.str(message, "guild_id");
        boolean allowColors = plugin.getConfig().getBoolean("ingame.allow-message-colors", false);

        String username = FluxerText.username(author);
        String displayName = FluxerText.displayName(author);
        String nickname = FluxerText.nickname(FluxerText.obj(message, "member"), author);

        // A reply carries the original message in "referenced_message" (null if it was deleted)
        JsonObject ref = FluxerText.obj(message, "referenced_message");

        String template = FluxerSRV.colorize(ref != null
                ? plugin.getConfig().getString("ingame.reply-format", DEFAULT_REPLY_FORMAT)
                : plugin.getConfig().getString("ingame.format", DEFAULT_FORMAT));

        String replyUsername = "";
        String replyDisplayName = "";
        String replyNickname = "";
        String replyPreview = "";
        if (ref != null && template.contains("%reply_")) {
            JsonObject replyAuthor = FluxerText.obj(ref, "author");
            replyUsername = replyAuthor != null ? FluxerText.username(replyAuthor) : "unknown";
            replyDisplayName = replyAuthor != null ? FluxerText.displayName(replyAuthor) : replyUsername;
            replyNickname = replyAuthor != null
                    ? FluxerText.nickname(FluxerText.obj(ref, "member"), replyAuthor) : replyUsername;
            String replyContent = FluxerText.str(ref, "content");
            if (replyContent == null) {
                replyContent = "";
            }

            // Replying to a line the bridge posted through the bot ("**Name**: text"):
            // show the Minecraft player's name and message instead of the bot's.
            boolean viaWebhook = ref.has("webhook_id") && !ref.get("webhook_id").isJsonNull();
            if (replyAuthor != null && FluxerText.bool(replyAuthor, "bot") && !viaWebhook) {
                String[] bridged = FluxerText.parseBridgedChat(plugin, replyContent);
                if (bridged != null) {
                    replyUsername = bridged[0];
                    replyDisplayName = bridged[0];
                    replyNickname = bridged[0];
                    replyContent = bridged[1];
                }
            }

            String refGuild = FluxerText.str(ref, "guild_id");
            int maxLength = plugin.getConfig().getInt("ingame.reply-preview-length", 50);
            replyPreview = FluxerText.preview(plugin, ref, refGuild != null ? refGuild : guildId,
                    replyContent, maxLength);
        }

        // Fill the (already colorized) template in one pass, so user-supplied text is never
        // re-scanned for placeholders and can't inject color codes of its own.
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value;
            switch (matcher.group(1)) {
                case "username":
                    value = clean(username);
                    break;
                case "display_name":
                    value = clean(displayName);
                    break;
                case "nickname":
                    value = clean(nickname);
                    break;
                case "reply_nickname":
                    value = clean(replyNickname);
                    break;
                case "reply_username":
                    value = clean(replyUsername);
                    break;
                case "reply_display_name":
                    value = clean(replyDisplayName);
                    break;
                case "reply_message":
                    value = clean(replyPreview);
                    break;
                default: // message
                    String restore = FluxerText.activeStyleAt(template, matcher.start());
                    value = FluxerText.resolve(plugin, message, guildId, content, true, allowColors, restore);
                    String attachments = FluxerText.attachmentLinks(plugin, message, restore);
                    if (!attachments.isEmpty()) {
                        // images / gifs / videos / files: clickable placeholders instead of nothing
                        value = value.trim().isEmpty() ? attachments : value + " " + attachments;
                    }
                    break;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);

        // "\n" in a format starts a new chat line
        List<String> lines = new ArrayList<>();
        for (String line : out.toString().split("\n")) {
            lines.add(line);
        }
        return lines;
    }

    private static String clean(String text) {
        return FluxerText.stripTokens(ChatColor.stripColor(text == null ? "" : text));
    }
}
