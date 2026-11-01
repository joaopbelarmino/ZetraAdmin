/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.command.CommandSender
 *  org.bukkit.entity.Player
 */
package me.zetra.zetraadmin.commands;

import me.zetra.zetraadmin.ZetraAdmin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class AdminCommand
implements CommandExecutor {
    private final ZetraAdmin plugin;

    public AdminCommand(ZetraAdmin plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            this.sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        StringBuilder fullCommand = new StringBuilder("/adm");
        for (String arg : args) {
            fullCommand.append(" ").append(arg);
        }
        this.plugin.getLogManager().log(sender.getName(), fullCommand.toString());
        switch (sub) {
            case "limites": {
                this.handleLimites(sender, args);
                break;
            }
            case "ban": 
            case "tempban": 
            case "banip": 
            case "tempbanip": 
            case "mute": 
            case "tempmute": {
                this.handlePunishment(sender, sub, args);
                break;
            }
            case "unban": {
                this.handleUnban(sender, args);
                break;
            }
            case "tp": {
                this.handleTeleport(sender, args);
                break;
            }
            case "gm": {
                this.handleGamemode(sender, args);
                break;
            }
            case "fly": {
                this.handleFly(sender, args);
                break;
            }
            case "speed": {
                this.handleSpeed(sender, args);
                break;
            }
            case "invsee": {
                this.handleInvsee(sender, args);
                break;
            }
            case "vanish": {
                this.handleVanish(sender);
                break;
            }
            case "god": {
                this.handleGod(sender, true);
                break;
            }
            case "ungod": {
                this.handleGod(sender, false);
                break;
            }
            default: {
                sender.sendMessage(String.valueOf(ChatColor.RED) + "Subcomando desconhecido. Use /adm para ajuda.");
            }
        }
        return true;
    }

    private void handleLimites(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zetraAdmin.admin")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o para configurar limites.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Uso: /adm limites <nick> <numero>");
            return;
        }
        String targetNick = args[1];
        try {
            int limit = Integer.parseInt(args[2]);
            this.plugin.getLimitManager().setLimit(targetNick, limit);
            sender.sendMessage(String.valueOf(ChatColor.GREEN) + "Limite de " + targetNick + " definido para " + limit + ".");
        }
        catch (NumberFormatException e) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "O limite deve ser um n\u00famero inteiro.");
        }
    }

    private void handlePunishment(CommandSender sender, String type, String[] args) {
        String permission = "zetraAdmin." + (type.contains("ban") ? "ban" : "mute");
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o para usar este comando.");
            return;
        }
        if (type.equals("banip") || type.equals("tempbanip")) {
            sender.sendMessage(String.valueOf(ChatColor.RED)
                    + "Ban por IP foi desativado para evitar bans vanilla presos. Use /adm ban ou /adm tempban por UUID.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Uso: /adm " + type + " <jogador> [argumentos...]");
            return;
        }
        if (sender instanceof Player) {
            Player staff = (Player)sender;
            if (!this.plugin.getLimitManager().canPerformAction(staff.getName())) {
                staff.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea atingiu seu limite di\u00e1rio de puni\u00e7\u00f5es!");
                return;
            }
        }
        if (type.equals("ban") || type.equals("tempban")) {
            if (!this.applyLocalBan(sender, type, args)) return;
            sender.sendMessage(String.valueOf(ChatColor.GREEN) + "Banimento aplicado pelo ZetraAdmin.");
            if (sender instanceof Player) this.plugin.getLimitManager().incrementUsed(sender.getName());
            return;
        }
        StringBuilder cmdBuilder = new StringBuilder(type);
        for (int i = 1; i < args.length; ++i) {
            cmdBuilder.append(" ").append(args[i]);
        }
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)cmdBuilder.toString());
        sender.sendMessage(String.valueOf(ChatColor.GREEN) + "Puni\u00e7\u00e3o executada via EssentialsX.");
        if (sender instanceof Player) {
            this.plugin.getLimitManager().incrementUsed(sender.getName());
        }
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zetraAdmin.ban")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o para usar este comando.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Uso: /adm unban <jogador>");
            return;
        }

        Optional<OfflinePlayer> target = this.plugin.getPanelSyncService().findKnownPlayer(args[1]);
        if (target.isPresent()) {
            String nick = target.get().getName() == null ? args[1] : target.get().getName();
            // Also clear a legacy Essentials/vanilla ban when there is no local active record.
            this.plugin.getPanelSyncService().recordLocalUnban(
                    target.get().getUniqueId(),
                    nick,
                    sender.getName(),
                    "adm"
            );
        } else {
            sender.sendMessage(String.valueOf(ChatColor.RED)
                    + "Jogador desconhecido no cache seguro. Confirme o nick ou aguarde o jogador entrar uma vez.");
            return;
        }
        sender.sendMessage(String.valueOf(ChatColor.GREEN) + "Unban agendado pelo ZetraAdmin.");
    }

    private boolean applyLocalBan(CommandSender sender, String type, String[] args) {
        Optional<OfflinePlayer> target = this.plugin.getPanelSyncService().findKnownPlayer(args[1]);
        if (target.isEmpty()) {
            sender.sendMessage(String.valueOf(ChatColor.RED)
                    + "Jogador desconhecido. O ZetraAdmin exige um UUID conhecido para banir com seguran\u00e7a.");
            return false;
        }

        long durationSeconds = 0L;
        int reasonStart = 2;
        if (type.equals("tempban")) {
            if (args.length < 3) {
                sender.sendMessage(String.valueOf(ChatColor.RED) + "Uso: /adm tempban <jogador> <duracao> [motivo]");
                return false;
            }
            durationSeconds = parseDurationSeconds(args[2]);
            if (durationSeconds <= 0L) {
                sender.sendMessage(String.valueOf(ChatColor.YELLOW)
                        + "Dura\u00e7\u00e3o inv\u00e1lida. Exemplos: 12h, 3d, 1w.");
                return false;
            }
            reasonStart = 3;
        }

        String reason = args.length > reasonStart
                ? String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length))
                : "Motivo n\u00e3o informado";
        OfflinePlayer offlinePlayer = target.get();
        String nick = offlinePlayer.getName() == null ? args[1] : offlinePlayer.getName();
        Instant expiresAt = durationSeconds > 0L ? Instant.now().plusSeconds(durationSeconds) : null;
        this.plugin.getPanelSyncService().recordLocalBan(
                offlinePlayer.getUniqueId(), nick, reason, sender.getName(), "adm", expiresAt
        );
        return true;
    }

    private long parseDurationSeconds(String raw) {
        String value = raw.toLowerCase(Locale.ROOT).trim();
        if (value.length() < 2) {
            return -1L;
        }
        int split = 0;
        while (split < value.length() && Character.isDigit(value.charAt(split))) {
            split++;
        }
        if (split == 0 || split == value.length()) {
            return -1L;
        }
        try {
            long amount = Long.parseLong(value.substring(0, split));
            String unit = value.substring(split);
            return switch (unit) {
                case "s", "sec", "secs", "second", "seconds" -> amount;
                case "m", "min", "mins", "minute", "minutes" -> TimeUnit.MINUTES.toSeconds(amount);
                case "h", "hour", "hours" -> TimeUnit.HOURS.toSeconds(amount);
                case "d", "day", "days" -> TimeUnit.DAYS.toSeconds(amount);
                case "w", "week", "weeks" -> TimeUnit.DAYS.toSeconds(Math.multiplyExact(amount, 7L));
                case "mo", "month", "months" -> TimeUnit.DAYS.toSeconds(Math.multiplyExact(amount, 30L));
                case "y", "year", "years" -> TimeUnit.DAYS.toSeconds(Math.multiplyExact(amount, 365L));
                default -> -1L;
            };
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1L;
        }
    }

    private void handleTeleport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zetraAdmin.tp")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o.");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Apenas jogadores podem usar TP.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Uso: /adm tp <jogador>");
            return;
        }
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("tp " + sender.getName() + " " + args[1]));
    }

    private void handleGamemode(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zetraAdmin.gm")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Uso: /adm gm <survival/spectator> [jogador]");
            return;
        }
        String mode = args[1].toLowerCase();
        if (!mode.equals("survival") && !mode.equals("spectator")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Modos suportados: survival, spectator.");
            return;
        }
        String target = args.length > 2 ? args[2] : sender.getName();
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("gamemode " + mode + " " + target));
    }

    private void handleFly(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zetraAdmin.fly")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o.");
            return;
        }
        String target = args.length > 1 ? args[1] : sender.getName();
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("fly " + target));
    }

    private void handleSpeed(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zetraAdmin.speed")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Uso: /adm speed <valor> [jogador]");
            return;
        }
        String value = args[1];
        String target = args.length > 2 ? args[2] : sender.getName();
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("speed " + value + " " + target));
    }

    private void handleInvsee(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zetraAdmin.invsee")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o.");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Apenas jogadores podem usar Invsee.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Uso: /adm invsee <jogador>");
            return;
        }
        Bukkit.dispatchCommand((CommandSender)((Player)sender), (String)("invsee " + args[1]));
    }

    private void handleVanish(CommandSender sender) {
        if (!sender.hasPermission("zetraAdmin.vanish")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o.");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Apenas jogadores podem usar Vanish.");
            return;
        }
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)("vanish " + sender.getName()));
    }

    private void handleGod(CommandSender sender, boolean enable) {
        if (!sender.hasPermission("zetraAdmin.god")) {
            sender.sendMessage(String.valueOf(ChatColor.RED) + "Voc\u00ea n\u00e3o tem permiss\u00e3o.");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Apenas jogadores podem usar God Mode.");
            return;
        }
        String cmd = enable ? "god " : "ungod ";
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)(cmd + sender.getName()));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(String.valueOf(ChatColor.GOLD) + "=== ZetraAdmin Ajuda ===");
        sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/adm ban/tempban/unban/mute... - Puni\u00e7\u00f5es");
        sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/adm tp <player> - Teleportar");
        sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/adm gm <survival/spectator> [player] - Gamemode");
        sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/adm fly [player] - Voar");
        sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/adm speed <valor> [player] - Velocidade");
        sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/adm invsee <player> - Ver Invent\u00e1rio");
        sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/adm vanish - Invisibilidade (Pr\u00f3prio)");
        sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/adm god/ungod - Modo Deus (Pr\u00f3prio)");
        if (sender.hasPermission("zetraAdmin.admin")) {
            sender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/adm limites <nick> <numero> - Configurar limites");
        }
    }
}
