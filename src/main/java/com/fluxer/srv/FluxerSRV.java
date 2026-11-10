package com.fluxer.srv;

import okhttp3.OkHttpClient;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FluxerSRV extends JavaPlugin {

    private FluxerClient fluxerClient;
    private OkHttpClient httpClient;

    private volatile String mainChannelId;

    // guildId -> cached role names (for rendering <@&id> role mentions)
    private final Map<String, CachedRoles> roleCache = new ConcurrentHashMap<>();
    private ChatPlaceholders chatPlaceholders;

    private static final class CachedRoles {
        final Map<String, String> names;
        final long fetchedAt;

        CachedRoles(Map<String, String> names, long fetchedAt) {
            this.names = names;
            this.fetchedAt = fetchedAt;
        }
    }
    private volatile String consoleChannelId;
    private String activeToken;

    private static final Pattern HEX_COLOR = Pattern.compile("&#([0-9a-fA-F]{6})");

    // Set once the chat webhook is ready (null = not ready / webhooks disabled)
    private volatile FluxerRestApi.Webhook chatWebhook;

    private com.fluxer.srv.logging.FluxerConsoleAppender consoleAppender;

    // Live player-count status
    private org.bukkit.scheduler.BukkitTask statusTask;
    private String lastStatusText;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String token = getConfig().getString("botToken");
        mainChannelId = getConfig().getString("channels.global");
        consoleChannelId = getConfig().getString("channels.console");

        if (token == null || token.isEmpty() || token.equals("Bot Token_Here")) {
            getLogger().severe("Bot token is not set! Please configure it in config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        activeToken = token;
        httpClient = new OkHttpClient();
        fluxerClient = new FluxerClient(token, getLogger(), httpClient);
        chatPlaceholders = new ChatPlaceholders(this);
        if (getServer().getPluginManager().getPlugin("InteractiveChat") != null) {
            getLogger().info("InteractiveChat detected: [item], [inv], [ender] and custom placeholders "
                    + "are sent to Fluxer as text (see interactivechat: in config.yml).");
        }

        // Register listeners and command executors
        getServer().getPluginManager().registerEvents(new com.fluxer.srv.listeners.ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new com.fluxer.srv.listeners.JoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new com.fluxer.srv.listeners.DeathListener(this), this);
        // Advancements: PlayerAdvancementDoneEvent on 1.12+, the old achievement event on 1.8-1.11
        if (!com.fluxer.srv.listeners.AdvancementHook.register(this)) {
            try {
                Class.forName("org.bukkit.event.player.PlayerAchievementAwardedEvent");
                getServer().getPluginManager().registerEvents(
                        new com.fluxer.srv.listeners.AdvancementListener(this), this);
            } catch (ClassNotFoundException e) {
                getLogger().warning("No advancement event found on this server; advancements won't be sent to Fluxer.");
            }
        }

        try {
            fluxerClient.setListener(new com.fluxer.srv.listeners.FluxerMessageListener(this));
            fluxerClient.connect();
            if (mainChannelId != null && !mainChannelId.isEmpty() && !mainChannelId.equals("000000000000000000")) {
                fluxerClient.getRestApi().sendMessage(mainChannelId,
                        getConfig().getString("formats.serverStart", "# Server Started \u2705"));
            }
        } catch (Exception e) {
            getLogger().severe("Failed to connect to Fluxer Gateway: " + e.getMessage());
            e.printStackTrace();
        }

        // Live player count on the bot's status
        startStatusTask();

        // Setup per-player chat webhook (async: it does blocking REST calls)
        startChatWebhookSetup();

        // Setup Console Appender
        try {
            org.apache.logging.log4j.core.Logger coreLogger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager
                    .getRootLogger();
            consoleAppender = new com.fluxer.srv.logging.FluxerConsoleAppender(this);
            consoleAppender.start();
            coreLogger.addAppender(consoleAppender);
            consoleAppender.startTask();
        } catch (Exception e) {
            getLogger().warning("Failed to initialize FluxerConsoleAppender. Console logs won't be forwarded.");
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        stopStatusTask();
        if (consoleAppender != null) {
            consoleAppender.stopTask();
            consoleAppender.stop();
            try {
                org.apache.logging.log4j.core.Logger coreLogger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager
                        .getRootLogger();
                coreLogger.removeAppender(consoleAppender);
            } catch (Exception ignored) {
            }
        }

        if (fluxerClient != null) {
            if (mainChannelId != null && !mainChannelId.isEmpty() && !mainChannelId.equals("000000000000000000")) {
                try {
                    fluxerClient.getRestApi().sendMessage(mainChannelId,
                            getConfig().getString("formats.serverStop", "# Server Stopped \u274C"));
                } catch (IOException e) {
                    getLogger().warning("Failed to send server stop message: " + e.getMessage());
                }
            }
            fluxerClient.shutdown();
        }

        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    private void startChatWebhookSetup() {
        chatWebhook = null;
        if (!getConfig().getBoolean("webhooks.enabled", false)) {
            return;
        }
        if (mainChannelId == null || mainChannelId.isEmpty() || mainChannelId.equals("000000000000000000")) {
            getLogger().warning("Webhooks are enabled but channels.global is not set; webhooks disabled.");
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, this::setupChatWebhook);
    }

    private void stopStatusTask() {
        if (statusTask != null) {
            statusTask.cancel();
            statusTask = null;
        }
    }

    /** (Re)starts the repeating task that pushes the live player count to the bot's status. */
    private void startStatusTask() {
        stopStatusTask();
        lastStatusText = null;
        if (fluxerClient == null || !getConfig().getBoolean("status.enabled", true)) {
            return;
        }

        int seconds = getConfig().getInt("status.update-interval-seconds", 30);
        if (seconds < MIN_STATUS_INTERVAL) {
            getLogger().warning("status.update-interval-seconds is below " + MIN_STATUS_INTERVAL
                    + "; using " + MIN_STATUS_INTERVAL + " to stay inside the gateway rate limit.");
            seconds = MIN_STATUS_INTERVAL;
        }
        long ticks = seconds * 20L;

        // Runs on the main thread so the player list is read safely; first run is immediate.
        statusTask = getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, 0L, ticks);
    }

    private static final int MIN_STATUS_INTERVAL = 15;

    private void refreshStatus() {
        int online = getServer().getOnlinePlayers().size();
        int max = getServer().getMaxPlayers();
        String text = getConfig().getString("status.format", "%online%/%max% players online")
                .replace("%online%", String.valueOf(online))
                .replace("%max%", String.valueOf(max));

        // Skip the gateway call if nothing changed
        if (text.equals(lastStatusText)) {
            return;
        }
        lastStatusText = text;

        String status = getConfig().getString("status.presence", "online").toLowerCase();
        if (!status.equals("online") && !status.equals("idle") && !status.equals("dnd")
                && !status.equals("invisible")) {
            status = "online";
        }
        fluxerClient.updatePresence(text, status);
    }

    /** Re-reads config.yml: channels, formats and webhook settings. The bot token needs a restart. */
    private void reloadFluxerConfig(CommandSender sender) {
        reloadConfig();
        roleCache.clear();
        mainChannelId = getConfig().getString("channels.global");
        consoleChannelId = getConfig().getString("channels.console");
        startChatWebhookSetup();
        startStatusTask();

        sender.sendMessage(ChatColor.GREEN + "FluxerSRV config reloaded.");
        String newToken = getConfig().getString("botToken");
        if (newToken != null && !newToken.equals(activeToken)) {
            sender.sendMessage(ChatColor.YELLOW + "The bot token changed; restart the server to apply it.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("fluxer")) {
            return false;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadFluxerConfig(sender);
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<String>();
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) {
            options.add("reload");
        }
        return options.isEmpty() ? Collections.<String>emptyList() : options;
    }

    /**
     * Translates '&' color codes, plus '&#RRGGBB' hex colors (1.16+ servers), into section-sign codes.
     */
    public static String colorize(String text) {
        Matcher matcher = HEX_COLOR.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder();
            replacement.append(ChatColor.COLOR_CHAR).append('x');
            for (char c : matcher.group(1).toCharArray()) {
                replacement.append(ChatColor.COLOR_CHAR).append(c);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }

    private void setupChatWebhook() {
        String url = getConfig().getString("webhooks.url", "");
        try {
            if (url != null && !url.trim().isEmpty()) {
                Matcher matcher = Pattern.compile("webhooks/(\\d+)/([^/?#\\s]+)").matcher(url.trim());
                if (!matcher.find()) {
                    getLogger().warning("webhooks.url is not a valid Fluxer webhook URL; using the bot for chat.");
                    return;
                }
                // Execute against the exact host/path the user supplied (e.g. https://fluxer.com/api/...)
                String executeUrl = url.trim().substring(0, matcher.end());
                if (!executeUrl.startsWith("http://") && !executeUrl.startsWith("https://")) {
                    getLogger().warning("webhooks.url must start with http:// or https://; using the bot for chat.");
                    return;
                }
                chatWebhook = new FluxerRestApi.Webhook(matcher.group(1), matcher.group(2), executeUrl);
            } else {
                String name = getConfig().getString("webhooks.name", "FluxerSRV Chat");
                chatWebhook = fluxerClient.getRestApi().findOrCreateWebhook(mainChannelId, name);
            }
            getLogger().info("Chat webhook ready; player chat will be sent under each player's name.");
        } catch (Exception e) {
            getLogger().warning("Could not set up chat webhook (does the bot have Manage Webhooks?): "
                    + e.getMessage());
        }
    }

    /**
     * Looks up a role's name for rendering mentions. Cached for 5 minutes, and re-fetched
     * (at most every 30 seconds) when an id isn't in the cache. Returns null if unknown.
     */
    public String resolveRoleName(String guildId, String roleId) {
        if (guildId == null || guildId.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        CachedRoles cached = roleCache.get(guildId);
        boolean stale = cached == null
                || now - cached.fetchedAt > 300000L
                || (!cached.names.containsKey(roleId) && now - cached.fetchedAt > 30000L);
        if (stale) {
            try {
                cached = new CachedRoles(fluxerClient.getRestApi().getGuildRoles(guildId), now);
            } catch (Exception e) {
                // Remember the failure for a while so we don't retry on every message
                cached = new CachedRoles(cached != null ? cached.names : new ConcurrentHashMap<String, String>(), now);
            }
            roleCache.put(guildId, cached);
        }
        return cached.names.get(roleId);
    }

    /** True if the given webhook id is the one this plugin posts chat through. */
    public boolean isOwnWebhook(String webhookId) {
        FluxerRestApi.Webhook hook = chatWebhook;
        return hook != null && hook.id.equals(webhookId);
    }

    /**
     * Sends a player's chat line to the global channel, via the webhook (own name + avatar)
     * when enabled, otherwise through the bot using botMessage. Call from an async thread.
     */
    public void sendPlayerChat(String playerName, String playerUuid, String rawMessage, String botMessage) {
        sendPlayerChat(playerName, playerUuid, rawMessage, botMessage, null);
    }

    /** The player's head icon: the same URL (webhooks.avatar-url) the webhook uses as their avatar. */
    public String playerAvatarUrl(String playerName, String playerUuid) {
        return getConfig()
                .getString("webhooks.avatar-url", "https://heads.discordsrv.com/%player_name%/helm/256")
                .replace("%player_name%", playerName)
                .replace("%player_uuid%", playerUuid);
    }

    /** Embeds with the title as the author line, plus the player's head as its icon when the embed asks for it. */
    public java.util.List<com.google.gson.JsonObject> buildEmbeds(String playerName, String playerUuid,
                                                                  java.util.List<ChatPlaceholders.Embed> embeds) {
        String icon = playerAvatarUrl(playerName, playerUuid);
        java.util.List<com.google.gson.JsonObject> out = new java.util.ArrayList<>();
        for (ChatPlaceholders.Embed embed : embeds) {
            out.add(FluxerRestApi.authorEmbed(embed.title, embed.icon ? icon : null, embed.body, embed.color));
        }
        return out;
    }

    /** As above; the embeds (if any) are posted in the same message. */
    public void sendPlayerChat(String playerName, String playerUuid, String rawMessage, String botMessage,
                               java.util.List<ChatPlaceholders.Embed> embedList) {
        FluxerRestApi api = fluxerClient.getRestApi();
        java.util.List<com.google.gson.JsonObject> embeds = embedList == null || embedList.isEmpty()
                ? null : buildEmbeds(playerName, playerUuid, embedList);
        boolean fallback = getConfig().getBoolean("webhooks.fallback-to-bot", true);

        if (getConfig().getBoolean("webhooks.enabled", false)) {
            FluxerRestApi.Webhook hook = chatWebhook;
            if (hook != null) {
                try {
                    String username = getConfig().getString("webhooks.username-format", "%player_name%")
                            .replace("%player_name%", playerName)
                            .replace("%player_uuid%", playerUuid);
                    String avatar = playerAvatarUrl(playerName, playerUuid);
                    String content = getConfig().getString("webhooks.content-format", "%message%")
                            .replace("%player_name%", playerName)
                            .replace("%message%", stripMassMentions(rawMessage));
                    api.executeWebhook(hook, content, username, avatar, embeds);
                    return;
                } catch (Exception e) {
                    getLogger().warning("Failed to send chat via webhook: " + e.getMessage());
                }
            }
            if (!fallback) {
                return;
            }
        }

        try {
            if (embeds != null) {
                api.sendMessage(mainChannelId, botMessage, embeds);
            } else {
                api.sendMessage(mainChannelId, botMessage);
            }
        } catch (Exception e) {
            getLogger().warning("Failed to send chat message to Fluxer: " + e.getMessage());
        }
    }

    // Webhook messages can ping, so defuse @everyone / @here coming from players.
    private static String stripMassMentions(String text) {
        return text.replace("@everyone", "@\u200beveryone").replace("@here", "@\u200bhere");
    }

    public ChatPlaceholders getChatPlaceholders() {
        return chatPlaceholders;
    }

    public FluxerClient getFluxerClient() {
        return fluxerClient;
    }

    public String getMainChannelId() {
        return mainChannelId;
    }

    public String getConsoleChannelId() {
        return consoleChannelId;
    }
}
