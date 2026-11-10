package com.fluxer.srv;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends chat lines containing clickable tokens (see {@link FluxerText}) using the Spigot chat API.
 * Kept in its own class so servers without that API only fail here, inside a try/catch.
 */
final class ClickableChat {

    private ClickableChat() {
    }

    /** Sends the line to every online player. Returns false if nothing could be sent clickably. */
    static boolean broadcast(FluxerSRV plugin, String line) {
        BaseComponent[] components = build(line);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.spigot().sendMessage(components);
        }
        return true;
    }

    static BaseComponent[] build(String line) {
        List<BaseComponent> result = new ArrayList<>();
        StringBuilder visible = new StringBuilder(); // everything emitted so far, to carry colors across parts

        FluxerText.walk(line, new FluxerText.TokenVisitor() {
            @Override
            public void text(String text) {
                if (text.isEmpty()) {
                    return;
                }
                String style = FluxerText.activeStyleAt(visible.toString(), visible.length());
                visible.append(text);
                for (BaseComponent part : TextComponent.fromLegacyText(style + text)) {
                    result.add(part);
                }
            }

            @Override
            public void link(String url, String display, String hover) {
                String style = FluxerText.activeStyleAt(visible.toString(), visible.length());
                visible.append(display);
                for (BaseComponent part : TextComponent.fromLegacyText(style + display)) {
                    part.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
                    if (hover != null) { // links carry no hover: just the clickable name
                        try {
                            part.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    new BaseComponent[]{new TextComponent(TextComponent.fromLegacyText(hover))}));
                        } catch (Throwable ignored) {
                            // hover text is a nicety; the click still works without it
                        }
                    }
                    result.add(part);
                }
            }
        });
        return result.toArray(new BaseComponent[0]);
    }
}
