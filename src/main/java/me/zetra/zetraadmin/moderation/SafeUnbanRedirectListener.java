package me.zetra.zetraadmin.moderation;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

public final class SafeUnbanRedirectListener implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String rewritten = rewrite(event.getMessage().substring(1));
        if (rewritten == null) return;
        event.setCancelled(true);
        Bukkit.dispatchCommand(event.getPlayer(), rewritten);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        String rewritten = rewrite(event.getCommand());
        if (rewritten != null) event.setCommand(rewritten);
    }

    static String rewrite(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.trim();
        if (command.isEmpty()) return null;
        String[] parts = command.split("\\s+", 2);
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("essentials:") || label.startsWith("minecraft:")) {
            label = label.substring(label.indexOf(':') + 1);
        }
        String action = switch (label) {
            case "ban", "tempban" -> label;
            case "unban", "pardon" -> "unban";
            default -> null;
        };
        if (action == null) return null;
        return "adm " + action + (parts.length == 2 ? " " + parts[1].trim() : "");
    }
}
