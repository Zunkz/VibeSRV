package com.fluxer.srv.listeners;

import com.fluxer.srv.FluxerSRV;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAchievementAwardedEvent;

public class AdvancementListener implements Listener {

    private final FluxerSRV plugin;

    public AdvancementListener(FluxerSRV plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAchievementDone(PlayerAchievementAwardedEvent event) {
        String title = event.getAchievement().name().replace("_", " ").toLowerCase();
        // Capitalize the first letters of words in the achievement name
        String[] words = title.split(" ");
        for (int i = 0; i < words.length; i++) {
            if (words[i].length() > 0) {
                words[i] = words[i].substring(0, 1).toUpperCase() + words[i].substring(1);
            }
        }
        title = String.join(" ", words);

        AdvancementHook.announce(plugin, event.getPlayer().getName(), title, "", "TASK");
    }
}
