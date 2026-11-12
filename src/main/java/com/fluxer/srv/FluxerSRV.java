package com.fluxer.srv;

import com.fluxer.srv.listeners.AdvancementHook;
import com.fluxer.srv.listeners.AdvancementListener;
import com.fluxer.srv.listeners.ChatListener;
import com.fluxer.srv.listeners.DeathListener;
import com.fluxer.srv.listeners.FluxerMessageListener;
import com.fluxer.srv.listeners.JoinQuitListener;
import com.fluxer.srv.logging.FluxerConsoleAppender;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

public class FluxerSRV extends JavaPlugin {

    private FluxerClient fluxerClient;
    private OkHttpClient httpClient;

    private volatile String mainChannelId;

    // guildId -> cached role names (for rendering <@&id> role mentions)
    private final Map<String, CachedRoles> roleCache = new ConcurrentHashMap<>();
    private ChatPlaceholders chatPlaceholders;
    private ItemIcons itemIcons;
    private final AtomicInteger inventoryPictures = new AtomicInteger();

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

    private static final String UNSET_CHANNEL = "000000000000000000";
    private static final Pattern HEX_COLOR = Pattern.compile("&#([0-9a-fA-F]{6})");

    // Set once the chat webhook is ready (null = not ready / webhooks disabled)
    private volatile FluxerRestApi.Webhook chatWebhook;

    private FluxerConsoleAppender consoleAppender;

    // Live player-count status
    private BukkitTask statusTask;
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
        itemIcons = new ItemIcons(this);
        itemIcons.generateAsync(); // inventory icons for [item], made once per Minecraft version
        if (getServer().getPluginManager().getPlugin("InteractiveChat") != null) {
            getLogger().info("InteractiveChat detected: [item], [inv], [ender] and custom placeholders "
                    + "are sent to Fluxer as text (see interactivechat: in config.yml).");
        }

        // Register listeners and command executors
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new DeathListener(this), this);
        // Advancements: PlayerAdvancementDoneEvent on 1.12+, the old achievement event on 1.8-1.11
        if (!AdvancementHook.register(this)) {
            try {
                Class.forName("org.bukkit.event.player.PlayerAchievementAwardedEvent");
                getServer().getPluginManager().registerEvents(
                        new AdvancementListener(this), this);
            } catch (ClassNotFoundException e) {
                getLogger().warning("No advancement event found on this server; advancements won't be sent to Fluxer.");
            }
        }

        try {
            fluxerClient.setListener(new FluxerMessageListener(this));
            fluxerClient.connect();
            if (hasMainChannel()) {
                EventEmbeds.send(this, mainChannelId,
                        papi(null, getConfig().getString("formats.serverStart", "# Server Started")),
                        "serverStart", 0x57F287);
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
            Logger coreLogger = (Logger) LogManager
                    .getRootLogger();
            consoleAppender = new FluxerConsoleAppender(this);
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
                Logger coreLogger = (Logger) LogManager
                        .getRootLogger();
                coreLogger.removeAppender(consoleAppender);
            } catch (Exception ignored) {
            }
        }

        if (fluxerClient != null) {
            if (hasMainChannel()) {
                try {
                    EventEmbeds.send(this, mainChannelId,
                            papi(null, getConfig().getString("formats.serverStop", "# Server Stopped")),
                            "serverStop", 0xED4245);
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
        if (!hasMainChannel()) {
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

        int seconds = getConfig().getInt("status.update-interval-seconds", 45);
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

    // ---- PlaceholderAPI ----

    private volatile Method papiMethod;

    /**
     * Fills in PlaceholderAPI placeholders (%vault_prefix%, %server_tps%, ...) for a player, or server-wide
     * ones when player is null. Returns the text unchanged if PlaceholderAPI is missing or switched off
     * (placeholderapi: false). Safe from any thread. Never run this on text a player typed.
     */
    public String papi(OfflinePlayer player, String text) {
        if (text == null || text.indexOf('%') < 0 || !getConfig().getBoolean("placeholderapi", true)
                || !getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return text;
        }
        final String prepared = builtinLocale(player, text);
        if (prepared.indexOf('%') < 0) {
            return prepared;
        }
        try {
            if (getServer().isPrimaryThread()) {
                return papiNow(player, prepared);
            }
            return getServer().getScheduler().callSyncMethod(this, () -> papiNow(player, prepared))
                    .get(2, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            return prepared; // PlaceholderAPI missing, incompatible or too slow: keep the %placeholders% as they are
        }
    }

    /**
     * %player_locale% is answered here instead of by PlaceholderAPI: the Player expansion looks the locale
     * up through obsolete NMS field names and prints a long NoSuchFieldException on new Minecraft versions.
     */
    private String builtinLocale(OfflinePlayer player, String text) {
        if (!text.contains("%player_locale%")) {
            return text;
        }
        String locale = "unknown";
        Player online = player == null ? null : player.getPlayer();
        if (online != null) {
            for (String name : new String[]{"locale", "getLocale"}) {
                try {
                    Object value = online.getClass().getMethod(name).invoke(online);
                    if (value != null) {
                        locale = value.toString();
                        break;
                    }
                } catch (Throwable ignored) {
                    // try the next method name
                }
            }
        }
        return text.replace("%player_locale%", locale);
    }

    private String papiNow(OfflinePlayer player, String text) throws Exception {
        Method method = papiMethod;
        if (method == null) {
            method = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                    .getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            papiMethod = method;
        }
        return (String) method.invoke(null, player, text);
    }

    private static final int MIN_STATUS_INTERVAL = 15;

    private void refreshStatus() {
        int online = getServer().getOnlinePlayers().size();
        int max = getServer().getMaxPlayers();
        String text = papi(null, getConfig().getString("status.format", "%online%/%max% players online"))
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

    /** The player's head icon: the same URL (webhooks.avatar-url) the webhook uses as their avatar. */
    public String playerAvatarUrl(String playerName, String playerUuid) {
        return getConfig()
                .getString("webhooks.avatar-url", "https://heads.discordsrv.com/%player_name%/helm/256")
                .replace("%player_name%", playerName)
                .replace("%player_uuid%", playerUuid);
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * The picture of a player for the [inv] picture's player slot, downloaded from
     * interactivechat.inventory.embed.image.player-url (%player% = the player who typed [inv]; default
     * https://heads.discordsrv.com/%player%/player). Null if it is switched off or cannot be fetched, which leaves
     * the slot empty. Blocking: call from an async thread.
     */
    private BufferedImage fetchPlayerPicture(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        String template = getConfig().getString("interactivechat.inventory.embed.image.player-url",
                "https://heads.discordsrv.com/%player%/player");
        if (template == null || template.trim().isEmpty()) {
            return null;
        }
        try {
            String url = template.trim().replace("%player%", URLEncoder.encode(playerName, "UTF-8"));
            OkHttpClient client = httpClient.newBuilder()
                    .callTimeout(8, TimeUnit.SECONDS).build();
            Request request = new Request.Builder().url(url).header("User-Agent", "FluxerSRV").build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    getLogger().warning("Could not get the player picture for [inv] from " + url + " (HTTP "
                            + response.code() + "); the player slot is left empty.");
                    return null;
                }
                return ImageIO.read(new ByteArrayInputStream(response.body().bytes()));
            }
        } catch (Exception e) {
            getLogger().warning("Could not get the player picture for [inv]: " + e + "; the player slot is left empty.");
            return null;
        }
    }

    /** Adds an uploaded picture to the message and points the embed's image/thumbnail slot at it. */
    private static void attachPicture(JsonObject embed, String slot, List<FluxerRestApi.Attachment> files,
                                      String name, byte[] png) {
        for (FluxerRestApi.Attachment existing : files) {
            if (existing.filename.equals(name)) {
                png = null;
                break;
            }
        }
        if (png != null) {
            files.add(new FluxerRestApi.Attachment(name, png));
        }
        JsonObject holder = new JsonObject();
        holder.addProperty("url", "attachment://" + name);
        embed.add(slot, holder);
    }

    /**
     * Builds the embed JSON for the given embeds. Pictures the embeds refer to (attachment://name.png)
     * are added to {@code files}.
     */
    public List<JsonObject> buildEmbeds(List<ChatPlaceholders.Embed> embeds, List<FluxerRestApi.Attachment> files) {
        List<JsonObject> out = new ArrayList<>();
        for (ChatPlaceholders.Embed embed : embeds) {
            JsonObject json = FluxerRestApi.authorEmbed(embed.title, null, embed.body, embed.color);
            // [inv]: a picture of the inventory screen with the player's items in it
            if (embed.slots != null) {
                int scale = Math.max(1, Math.min(6, getConfig().getInt("interactivechat.inventory.embed.image.scale", 3)));
                byte[] picture = InventoryImage.render(itemIcons, embed.slots, fetchPlayerPicture(embed.playerName),
                        scale, getLogger());
                if (picture != null) {
                    attachPicture(json, "image", files, "inventory-" + inventoryPictures.incrementAndGet() + ".png", picture);
                    if (!getConfig().getBoolean("interactivechat.inventory.embed.image.show-text", false)) {
                        json.remove("description");
                    }
                }
            }
            // [ender] / shulker box: a picture of the container screen with its contents in it
            if (embed.containerSlots != null && embed.containerSection != null) {
                String key = "interactivechat." + embed.containerSection + ".embed.image.";
                int scale = Math.max(1, Math.min(6, getConfig().getInt(key + "scale", 3)));
                byte[] picture = InventoryImage.renderContainer(itemIcons, embed.containerSlots,
                        embed.containerTexture, scale, getLogger());
                if (picture != null) {
                    attachPicture(json, "image", files,
                            embed.containerSection + "-" + inventoryPictures.incrementAndGet() + ".png", picture);
                    if (!getConfig().getBoolean(key + "show-text", false)) {
                        json.remove("description");
                    }
                }
            }
            // [item]: the item's generated inventory icon as the thumbnail
            if (embed.material != null && getConfig().getBoolean("interactivechat.item.embed.thumbnail", true)) {
                byte[] icon = itemIcons != null ? itemIcons.iconPng(embed.material) : null;
                String template = getConfig().getString("icons.thumbnail-url", "");
                if (icon != null) {
                    attachPicture(json, "thumbnail", files, embed.material + ".png", icon);
                } else if (template != null && !template.trim().isEmpty()) {
                    // No generated icon: use a public image URL instead (icons.thumbnail-url)
                    JsonObject holder = new JsonObject();
                    holder.addProperty("url", template.replace("%item%", embed.material));
                    json.add("thumbnail", holder);
                } else {
                    getLogger().warning("No thumbnail for [item] '" + embed.material + "': it is not in the "
                            + "icon atlases (plugins/FluxerSRV/icons/atlas_*.png). Check the console for "
                            + "'Item icons ready' or 'Could not generate item icons'.");
                }
            }
            out.add(json);
        }
        return out;
    }

    /**
     * Sends a player's chat line to the global channel, via the webhook (own name + avatar) when enabled,
     * otherwise through the bot using botMessage. Any embeds are posted in the same message.
     * Call from an async thread.
     */
    public void sendPlayerChat(String playerName, String playerUuid, String rawMessage, String botMessage,
                               List<ChatPlaceholders.Embed> embedList) {
        FluxerRestApi api = fluxerClient.getRestApi();
        List<FluxerRestApi.Attachment> files = new ArrayList<>();
        List<JsonObject> embeds = embedList == null || embedList.isEmpty()
                ? null : buildEmbeds(embedList, files);
        boolean fallback = getConfig().getBoolean("webhooks.fallback-to-bot", true);

        if (getConfig().getBoolean("webhooks.enabled", false)) {
            FluxerRestApi.Webhook hook = chatWebhook;
            if (hook != null) {
                try {
                    OfflinePlayer papiPlayer = papiPlayer(playerName, playerUuid);
                    String username = papi(papiPlayer, getConfig().getString("webhooks.username-format", "%player_name%")
                            .replace("%player_name%", playerName)
                            .replace("%player_uuid%", playerUuid));
                    String avatar = playerAvatarUrl(playerName, playerUuid);
                    String content = papi(papiPlayer, getConfig().getString("webhooks.content-format", "%message%")
                            .replace("%player_name%", playerName))
                            .replace("%message%", stripMassMentions(rawMessage));
                    api.executeWebhook(hook, content, username, avatar, embeds, files);
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
                api.sendMessage(mainChannelId, botMessage, embeds, files);
            } else {
                api.sendMessage(mainChannelId, botMessage);
            }
        } catch (Exception e) {
            getLogger().warning("Failed to send chat message to Fluxer: " + e.getMessage());
        }
    }

    private OfflinePlayer papiPlayer(String playerName, String playerUuid) {
        try {
            return getServer().getOfflinePlayer(UUID.fromString(playerUuid));
        } catch (Exception e) {
            return null;
        }
    }

    /** Defuses @everyone / @here so text from players, item names and lore can never ping. */
    public static String stripMassMentions(String text) {
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

    /** True if the id is a real channel id (not empty and not the "000..." placeholder from the default config). */
    public static boolean isChannelSet(String channelId) {
        return channelId != null && !channelId.isEmpty() && !channelId.equals(UNSET_CHANNEL);
    }

    public boolean hasMainChannel() {
        return isChannelSet(mainChannelId);
    }

    public boolean hasConsoleChannel() {
        return isChannelSet(consoleChannelId);
    }
}
