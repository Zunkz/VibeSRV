package com.fluxer.srv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.ChatColor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for turning Fluxer message content into text for Minecraft chat:
 * resolves &lt;@id&gt; / &lt;@&amp;id&gt; / &lt;#id&gt; / custom emoji, and builds reply previews.
 */
public final class FluxerText {
    // user mention, role mention, channel mention, custom emoji
    private static final Pattern TOKEN = Pattern.compile("<(?:@!?(\\d+)|@&(\\d+)|#(\\d+)|a?:(\\w+):\\d+)>");
    private static final String SECTION = String.valueOf(ChatColor.COLOR_CHAR);

    private static final Set<String> MEDIA_EXTENSIONS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "apng", "avif", "heic", "svg",
            "mp4", "mov", "webm", "mkv", "avi", "m4v"));

    // Clickable parts (links, attachments) travel through the string pipeline as
    //   TOKEN_START + url + TOKEN_SEP + display text + TOKEN_END
    // using private-use characters, and are turned into real click events at send time.
    // User-supplied text is stripped of these characters so it can't forge a token.
    public static final char TOKEN_START = '\uE000';
    public static final char TOKEN_SEP = '\uE002';
    public static final char TOKEN_END = '\uE001';
    // optional: separates the display text from the hover text inside a token
    public static final char TOKEN_HOVER = '\uE003';

    // http(s) links, optionally wrapped in <...> (Discord-style "no embed" links)
    // Group 1/2: markdown link "[name](url)"; group 3: bare link.
    private static final Pattern URL = Pattern.compile(
            "\\[([^\\]\\n]+)\\]\\(<?(https?://(?:[^\\s<>()]|\\([^\\s<>()]*\\))+)>?\\)|<?(https?://[^\\s<>]+)>?",
            Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_LINK_LENGTH = 40;
    private static final String DEFAULT_LINK_FORMAT = "&b&n%name%";

    private FluxerText() {
    }

    /** Receives the pieces of a line that may contain clickable tokens. */
    public interface TokenVisitor {
        void text(String text);

        /** {@code hover} is the hover text (may contain color codes and newlines), or null. */
        void link(String url, String display, String hover);
    }

    /** Removes the reserved token characters from untrusted text. */
    public static String stripTokens(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(String.valueOf(TOKEN_START), "")
                .replace(String.valueOf(TOKEN_SEP), "")
                .replace(String.valueOf(TOKEN_HOVER), "")
                .replace(String.valueOf(TOKEN_END), "");
    }

    public static boolean hasTokens(String line) {
        return line != null && line.indexOf(TOKEN_START) >= 0;
    }

    /** Walks a line, calling {@code text} for plain stretches and {@code link} for clickable tokens. */
    public static void walk(String line, TokenVisitor visitor) {
        int i = 0;
        while (i < line.length()) {
            int start = line.indexOf(TOKEN_START, i);
            if (start < 0) {
                visitor.text(line.substring(i));
                return;
            }
            int sep = line.indexOf(TOKEN_SEP, start + 1);
            int end = line.indexOf(TOKEN_END, start + 1);
            if (sep < 0 || end < 0 || sep > end) {
                // malformed marker: keep the text, drop the marker
                visitor.text(line.substring(i, start));
                i = start + 1;
                continue;
            }
            if (start > i) {
                visitor.text(line.substring(i, start));
            }
            String shown = line.substring(sep + 1, end);
            String hover = null;
            int hoverAt = shown.indexOf(TOKEN_HOVER);
            if (hoverAt >= 0) {
                hover = shown.substring(hoverAt + 1);
                shown = shown.substring(0, hoverAt);
            }
            visitor.link(line.substring(start + 1, sep), shown, hover);
            i = end + 1;
        }
    }

    /** The line as plain text; optionally with each clickable token followed by "(url)". */
    public static String plain(String line, boolean includeUrls) {
        StringBuilder out = new StringBuilder();
        walk(line, new TokenVisitor() {
            @Override
            public void text(String text) {
                out.append(text);
            }

            @Override
            public void link(String url, String display, String hover) {
                out.append(display);
                if (includeUrls) { // only for players who can't click (no Spigot chat API)
                    out.append(" (").append(url).append(')');
                }
            }
        });
        return out.toString();
    }

    /**
     * Sends a line to everyone online (and the console). Lines with clickable tokens are sent
     * with real click events; if the server can't do that, the URLs are written out instead.
     */
    public static void broadcast(FluxerSRV plugin, String line) {
        if (!hasTokens(line)) {
            plugin.getServer().broadcastMessage(line);
            return;
        }
        String fallback = plain(line, true);
        boolean clickable = false;
        try {
            clickable = ClickableChat.broadcast(plugin, line);
        } catch (Throwable ignored) {
            // Spigot chat API missing: fall through to the plain version
        }
        if (!clickable) {
            for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
                player.sendMessage(fallback);
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(plain(line, false));
    }

    private static boolean isMedia(JsonObject attachment) {
        String type = str(attachment, "content_type");
        String name = str(attachment, "filename");
        if (type != null && (type.startsWith("image/") || type.startsWith("video/"))) {
            return true;
        }
        return name != null && name.contains(".")
                && MEDIA_EXTENSIONS.contains(name.substring(name.lastIndexOf('.') + 1).toLowerCase());
    }

    /**
     * Clickable names for a message's attachments (one per attachment, space separated), or ""
     * if there are none. Each shows only the file name and opens the attachment's URL.
     * {@code restore} is the style to hand back to the surrounding message text afterwards.
     */
    public static String attachmentLinks(FluxerSRV plugin, JsonObject msg, String restore) {
        if (msg == null || !msg.has("attachments") || !msg.get("attachments").isJsonArray()) {
            return "";
        }
        String mediaFormat = plugin.getConfig().getString("ingame.media-placeholder", DEFAULT_LINK_FORMAT);
        String fileFormat = plugin.getConfig().getString("ingame.file-placeholder", DEFAULT_LINK_FORMAT);
        StringBuilder out = new StringBuilder();
        for (JsonElement element : msg.getAsJsonArray("attachments")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject attachment = element.getAsJsonObject();
            String format = isMedia(attachment) ? mediaFormat : fileFormat;
            if (format == null || format.isEmpty()) {
                continue; // hidden
            }
            String display = linkDisplay(format, attachmentName(attachment));
            String url = str(attachment, "url");
            if (url == null || url.isEmpty()) {
                url = str(attachment, "proxy_url");
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            if (url != null && isHttp(url)) {
                String cleanUrl = stripTokens(url).replace(SECTION, "");
                out.append(TOKEN_START).append(cleanUrl).append(TOKEN_SEP).append(display).append(TOKEN_END);
            } else {
                out.append(display);
            }
            out.append(ChatColor.RESET).append(restore);
        }
        return out.toString();
    }

    /** The attachment's file name (never empty, never contains color or token characters). */
    private static String attachmentName(JsonObject attachment) {
        String name = hoverSafe(str(attachment, "filename"));
        return name.isEmpty() ? "attachment" : name;
    }

    /**
     * Applies a configured display format to a name. "%name%" is replaced by the name; the old
     * "&lt;media&gt;" / "&lt;file&gt;" / "&lt;link&gt;" defaults count as "%name%" too, so existing
     * config files show names without being edited. The format is colorized before the name goes in,
     * so a name can't smuggle in color codes.
     */
    private static String linkDisplay(String format, String name) {
        String template = FluxerSRV.colorize(stripTokens(format))
                .replace("<media>", "%name%").replace("<file>", "%name%").replace("<link>", "%name%");
        return template.replace("%name%", name);
    }

    private static String hoverSafe(String text) {
        if (text == null) {
            return "";
        }
        return stripTokens(text).replace(SECTION, "").replace("\n", " ").replace("\r", " ").trim();
    }

    private static boolean isHttp(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Plain, comma separated attachment names for reply previews, or "" if there are none. */
    public static String attachmentTag(FluxerSRV plugin, JsonObject msg) {
        if (msg == null || !msg.has("attachments") || !msg.get("attachments").isJsonArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonElement element : msg.getAsJsonArray("attachments")) {
            if (!element.isJsonObject()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(attachmentName(element.getAsJsonObject()));
        }
        return out.toString();
    }

    // ---- small JSON helpers ----

    /** String value of a key, or null if missing / null / not a primitive. */
    public static String str(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    public static boolean bool(JsonObject object, String key) {
        String value = str(object, key);
        return value != null && value.equalsIgnoreCase("true");
    }

    public static JsonObject obj(JsonObject object, String key) {
        if (object != null && object.has(key) && object.get(key).isJsonObject()) {
            return object.getAsJsonObject(key);
        }
        return null;
    }

    public static String username(JsonObject user) {
        String name = str(user, "username");
        return name != null ? name : "unknown";
    }

    /** global_name when set, otherwise the username. */
    public static String displayName(JsonObject user) {
        String global = str(user, "global_name");
        return (global != null && !global.isEmpty()) ? global : username(user);
    }

    /** The member's server nickname when set, otherwise the user's display name. */
    public static String nickname(JsonObject member, JsonObject user) {
        String nick = str(member, "nick");
        return (nick != null && !nick.isEmpty()) ? nick : displayName(user);
    }

    // ---- mention / emoji resolution ----

    /**
     * Resolves mentions and emoji in {@code content}.
     *
     * @param msg          the message the content belongs to (source of mentions / users / mention_channels)
     * @param styled       true: use the configured mention formats (with color); false: plain "@name" / "#name"
     * @param allowColors  translate the author's own &amp;-codes (only when styled)
     * @param restoreStyle color codes to re-apply after each styled mention so the message keeps its color
     */
    public static String resolve(FluxerSRV plugin, JsonObject msg, String guildId, String content,
                                 boolean styled, boolean allowColors, String restoreStyle) {
        Map<String, JsonObject> users = new HashMap<>();
        collectUsers(msg, "mentions", users);
        collectUsers(msg, "users", users);

        Map<String, String> channels = new HashMap<>();
        if (msg != null && msg.has("mention_channels") && msg.get("mention_channels").isJsonArray()) {
            for (JsonElement element : msg.getAsJsonArray("mention_channels")) {
                if (element.isJsonObject()) {
                    String id = str(element.getAsJsonObject(), "id");
                    String name = str(element.getAsJsonObject(), "name");
                    if (id != null && name != null) {
                        channels.put(id, name);
                    }
                }
            }
        }

        String nameSource = plugin.getConfig().getString("ingame.mentions.name-source", null);
        if (nameSource == null) {
            // older configs used a boolean
            nameSource = plugin.getConfig().getBoolean("ingame.mentions.use-display-name", false)
                    ? "display_name" : "username";
        }
        String userFormat = plugin.getConfig().getString("ingame.mentions.user-format", "&b@%name%");
        String roleFormat = plugin.getConfig().getString("ingame.mentions.role-format", "&b@%name%");
        String channelFormat = plugin.getConfig().getString("ingame.mentions.channel-format", "&b#%name%");

        boolean colorSegments = styled && allowColors;
        StringBuilder out = new StringBuilder();
        Matcher matcher = TOKEN.matcher(content);
        int last = 0;
        while (matcher.find()) {
            out.append(segment(plugin, content.substring(last, matcher.start()), colorSegments, styled, restoreStyle));
            last = matcher.end();

            if (matcher.group(1) != null) {
                JsonObject user = users.get(matcher.group(1));
                String name;
                if (user == null) {
                    name = "unknown-user";
                } else if (nameSource.equalsIgnoreCase("nickname")) {
                    name = nickname(obj(user, "member"), user);
                } else if (nameSource.equalsIgnoreCase("display_name")) {
                    name = displayName(user);
                } else {
                    name = username(user);
                }
                out.append(mention(userFormat, name, "@", styled, restoreStyle));
            } else if (matcher.group(2) != null) {
                String name = plugin.resolveRoleName(guildId, matcher.group(2));
                out.append(mention(roleFormat, name == null ? "unknown-role" : name, "@", styled, restoreStyle));
            } else if (matcher.group(3) != null) {
                String name = channels.get(matcher.group(3));
                out.append(mention(channelFormat, name == null ? "unknown-channel" : name, "#", styled, restoreStyle));
            } else {
                out.append(':').append(matcher.group(4)).append(':');
            }
        }
        out.append(segment(plugin, content.substring(last), colorSegments, styled, restoreStyle));
        return out.toString();
    }

    private static void collectUsers(JsonObject msg, String key, Map<String, JsonObject> into) {
        if (msg == null || !msg.has(key) || !msg.get(key).isJsonArray()) {
            return;
        }
        JsonArray array = msg.getAsJsonArray(key);
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                String id = str(element.getAsJsonObject(), "id");
                if (id != null) {
                    into.put(id, element.getAsJsonObject());
                }
            }
        }
    }

    /**
     * Text between mentions: drop raw section signs and token characters, turn links into the
     * configured link placeholder (clickable when styled), optionally translate &-codes.
     */
    private static String segment(FluxerSRV plugin, String text, boolean colorize, boolean styled, String restore) {
        String clean = stripTokens(text).replace(SECTION, "");
        Matcher matcher = URL.matcher(clean);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String label = matcher.group(1);
            String url;
            int before;
            int after = matcher.end();
            if (label != null) { // [name](url)
                url = matcher.group(2);
                before = matcher.start();
            } else {
                boolean wrapped = matcher.group().startsWith("<") && matcher.group().endsWith(">");
                url = matcher.group(3);
                before = wrapped ? matcher.start() : matcher.start(3);
                if (!wrapped) {
                    // leave trailing punctuation outside the link: "see https://x.com/a."
                    while (url.length() > 0 && isTrailing(url)) {
                        url = url.substring(0, url.length() - 1);
                    }
                    after = matcher.start(3) + url.length();
                }
            }
            if (!isHttp(url) || url.indexOf("://") + 3 >= url.length()) {
                continue;
            }
            String between = clean.substring(last, before);
            out.append(colorize ? FluxerSRV.colorize(between) : between);
            out.append(link(plugin, url, label, styled, restore));
            last = after;
        }
        String tail = clean.substring(last);
        out.append(colorize ? FluxerSRV.colorize(tail) : tail);
        return out.toString();
    }

    private static boolean isTrailing(String url) {
        char c = url.charAt(url.length() - 1);
        if (".,;:!?'\"".indexOf(c) >= 0) {
            return true;
        }
        if (c == ')') {
            return count(url, '(') < count(url, ')');
        }
        return false;
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** A link in message text: shows just its name ([name](url) label, or the shortened URL). */
    private static String link(FluxerSRV plugin, String url, String label, boolean styled, String restore) {
        String name = label != null ? label.trim() : shorten(plugin, url);
        name = stripTokens(name).replace(SECTION, "").replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            name = shorten(plugin, url);
        }
        if (!styled) {
            return name; // reply previews: plain text
        }
        String format = plugin.getConfig().getString("ingame.link-placeholder", DEFAULT_LINK_FORMAT);
        if (format == null || format.isEmpty()) {
            format = DEFAULT_LINK_FORMAT;
        }
        String display = linkDisplay(format, name);
        return String.valueOf(TOKEN_START) + url + TOKEN_SEP + display + TOKEN_END
                + ChatColor.RESET + restore;
    }

    /** "https://www.example.com/a/b/" -> "example.com/a/b", cut to ingame.link-max-length. */
    private static String shorten(FluxerSRV plugin, String url) {
        String name = url.replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^www\\.", "");
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        int max = plugin.getConfig().getInt("ingame.link-max-length", DEFAULT_LINK_LENGTH);
        if (max > 3 && name.length() > max) {
            name = name.substring(0, max - 3) + "...";
        }
        return name;
    }

    private static String mention(String format, String name, String plainPrefix, boolean styled, String restore) {
        String clean = stripTokens(name).replace(SECTION, "");
        if (!styled) {
            return plainPrefix + clean;
        }
        // Colorize the template first so codes inside the name can't leak through
        return FluxerSRV.colorize(format).replace("%name%", clean) + restore;
    }

    // ---- replies ----

    /**
     * Short single-line preview of the replied-to message, with mentions as plain text.
     * {@code rawContent} is passed in so callers can unwrap bridged "**Name**: text" lines first.
     */
    public static String preview(FluxerSRV plugin, JsonObject ref, String guildId, String rawContent, int maxLength) {
        String text;
        if (rawContent == null || rawContent.trim().isEmpty()) {
            String tag = attachmentTag(plugin, ref);
            if (!tag.isEmpty()) {
                text = tag;
            } else if (hasItems(ref, "embeds")) {
                text = "[embed]";
            } else {
                text = "";
            }
        } else {
            text = resolve(plugin, ref, guildId, rawContent, false, false, "");
        }

        text = text.replaceAll("\\s+", " ").trim();
        if (maxLength > 0 && text.length() > maxLength) {
            text = text.substring(0, maxLength).trim() + "...";
        }
        return text;
    }

    private static boolean hasItems(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                && object.getAsJsonArray(key).size() > 0;
    }

    /**
     * If {@code content} looks like a line the bridge itself posted via the bot (per formats.chat),
     * returns {playerName, message}; otherwise null.
     */
    public static String[] parseBridgedChat(FluxerSRV plugin, String content) {
        String format = plugin.getConfig().getString("formats.chat", "**%player_name%**: %message%");
        int name = format.indexOf("%player_name%");
        int message = format.indexOf("%message%");
        if (name < 0 || message < 0 || name > message) {
            return null;
        }
        String before = format.substring(0, name);
        String between = format.substring(name + "%player_name%".length(), message);
        String after = format.substring(message + "%message%".length());
        Pattern pattern = Pattern.compile("^" + Pattern.quote(before) + "(.+?)" + Pattern.quote(between)
                + "(.*)" + Pattern.quote(after) + "$", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);
        if (!matcher.matches()) {
            return null;
        }
        return new String[]{matcher.group(1), matcher.group(2)};
    }

    // ---- color helpers ----

    /**
     * The color/format codes in effect just before {@code end} in an already-colorized string,
     * so text inserted there can hand the style back afterwards (e.g. "\u00a77" or "\u00a7a\u00a7l").
     */
    public static String activeStyleAt(String colorized, int end) {
        String color = "";
        StringBuilder formats = new StringBuilder();
        int i = 0;
        while (i + 1 < end) {
            if (colorized.charAt(i) != ChatColor.COLOR_CHAR) {
                i++;
                continue;
            }
            char code = Character.toLowerCase(colorized.charAt(i + 1));
            if (code == 'x' && isHexRun(colorized, i, end)) {
                color = colorized.substring(i, i + 14);
                formats.setLength(0);
                i += 14;
                continue;
            }
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                color = SECTION + code;
                formats.setLength(0);
            } else if (code == 'r') {
                color = "";
                formats.setLength(0);
            } else if (code >= 'k' && code <= 'o') {
                formats.append(ChatColor.COLOR_CHAR).append(code);
            }
            i += 2;
        }
        return color + formats;
    }

    // §x§R§R§G§G§B§B  (14 chars)
    private static boolean isHexRun(String s, int start, int end) {
        if (start + 14 > end) {
            return false;
        }
        for (int k = 0; k < 6; k++) {
            int at = start + 2 + k * 2;
            if (s.charAt(at) != ChatColor.COLOR_CHAR || Character.digit(s.charAt(at + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
