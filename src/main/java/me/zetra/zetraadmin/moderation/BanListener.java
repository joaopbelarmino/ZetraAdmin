package me.zetra.zetraadmin.moderation;

import java.time.Duration;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import me.zetra.zetraadmin.ZetraAdmin;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;

public final class BanListener implements Listener {
    private final ZetraAdmin plugin;
    private final ModerationStore store;
    // Record cancellation only for kicks started by this listener.
    private final Map<UUID, Boolean> kickAttempts = new HashMap<>();

    public BanListener(ZetraAdmin plugin, ModerationStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        store.rememberPlayer(event.getUniqueId(), event.getName());
        Optional<BanRecord> active = store.activeBan(event.getUniqueId(), event.getName());
        if (active.isEmpty()) return;
        BanRecord ban = active.get();
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, buildMessage(ban));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        store.activeBan(player.getUniqueId(), player.getName()).ifPresent(ban ->
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, buildMessage(ban)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Floodgate/proxies can change the identity or login result after pre-login.
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) enforce(player);
        });
    }

    public boolean enforce(Player player) {
        Optional<BanRecord> active = store.activeBan(player.getUniqueId(), player.getName());
        if (active.isEmpty()) return true;
        UUID uuid = player.getUniqueId();
        kickAttempts.put(uuid, false);
        try {
            player.kickPlayer(buildMessage(active.get()));
            boolean cancelled = Boolean.TRUE.equals(kickAttempts.get(uuid));
            if (cancelled) plugin.getLogger().warning("Kick de ban cancelado por outro plugin: " + player.getName());
            return !cancelled;
        } finally {
            kickAttempts.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeBanKick(PlayerKickEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (kickAttempts.containsKey(uuid)) kickAttempts.put(uuid, event.isCancelled());
    }

    private String buildMessage(BanRecord ban) {
        String title = plugin.getConfig().getString("ban-message.title", "&5&lVoce foi banido do ZetraMC");
        String support = plugin.getConfig().getString("ban-message.support", "&fRecorra em &d&lsuporte.zetramc.online");
        String duration = ban.isPermanent() ? "Permanente" : remaining(ban.expiresAt());
        return color(title)
            + "\n\n" + color("&fMotivo:\n&d" + ban.reason())
            + "\n\n" + color("&fDuracao:\n&d" + duration)
            + "\n\n" + color(support);
    }

    private String remaining(long expiresAt) {
        long millis = Math.max(0L, expiresAt - System.currentTimeMillis());
        Duration duration = Duration.ofMillis(millis);
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return Math.max(1L, minutes) + "m";
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
