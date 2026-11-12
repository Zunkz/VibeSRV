package com.fluxer.srv.listeners;

import com.fluxer.srv.AdvancementNames;
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
        String known = AdvancementNames.achievementTitle(event.getAchievement().name());
        if (known != null) {
            AdvancementHook.announce(plugin, event.getPlayer().getName(), known, "", "TASK");
            return;
        }
        String title = AdvancementHook.titleFromKey(event.getAchievement().name().toLowerCase());
        AdvancementHook.announce(plugin, event.getPlayer().getName(), title, "", "TASK");
    }
}
