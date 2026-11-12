package com.fluxer.srv.listeners;

import com.fluxer.srv.EventEmbeds;
import com.fluxer.srv.FluxerSRV;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class DeathListener implements Listener {

    private final FluxerSRV plugin;

    public DeathListener(FluxerSRV plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.hasMainChannel()) {
            return;
        }
        String mainChannelId = plugin.getMainChannelId();

        String deathMessage = event.getDeathMessage();
        if (deathMessage == null || deathMessage.isEmpty()) {
            return;
        }

        String format = plugin.getConfig().getString("formats.death", "%death_message%");
        String message = plugin.papi(event.getEntity(), format).replace("%death_message%", deathMessage);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                EventEmbeds.send(plugin, mainChannelId, message, "death", 0x2F3136);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send death message to Fluxer: " + e.getMessage());
            }
        });
    }
}
