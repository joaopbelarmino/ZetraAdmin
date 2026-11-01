package me.zetra.zetraadmin.moderation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import me.zetra.zetraadmin.ZetraAdmin;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.profile.PlayerProfile;

public final class LegacyBanMigrator {
    private final ZetraAdmin plugin;
    private final ModerationStore store;

    public LegacyBanMigrator(ZetraAdmin plugin, ModerationStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public int migrate() {
        int imported = migrateList(BanList.Type.PROFILE) + migrateList(BanList.Type.NAME);
        if (imported > 0) plugin.getLogger().info(imported + " banimento(s) vanilla/Essentials convertido(s) para o cache exclusivo do ZetraAdmin.");
        return imported;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int migrateList(BanList.Type type) {
        int imported = 0;
        BanList list = Bukkit.getBanList(type);
        for (Object value : new ArrayList<>(list.getEntries())) {
            BanEntry entry = (BanEntry) value;
            PlayerIdentity identity = identity(entry);
            if (identity == null) continue;
            long createdAt = entry.getCreated() == null ? System.currentTimeMillis() : entry.getCreated().getTime();
            Date expiration = entry.getExpiration();
            long expiresAt = expiration == null ? 0L : expiration.getTime();
            boolean alreadyActive = store.activeBan(identity.uuid(), identity.nick()).isPresent();
            boolean newerThanLocalState = createdAt > store.latestStateVersion(identity.uuid());
            if (!alreadyActive && newerThanLocalState) {
                String eventId = UUID.nameUUIDFromBytes(("legacy-ban:" + identity.uuid() + ":" + createdAt).getBytes(StandardCharsets.UTF_8)).toString();
                store.applyBan(
                    eventId,
                    identity.uuid(),
                    identity.nick(),
                    safe(entry.getReason(), "Motivo nao informado"),
                    safe(entry.getSource(), "Sistema antigo"),
                    "vanilla_migration",
                    createdAt,
                    expiresAt,
                    "",
                    true
                );
                imported++;
            }
            clearVanillaBan(identity);
        }
        return imported;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void clearVanillaBan(PlayerIdentity identity) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(identity.uuid());
        BanList profileBans = Bukkit.getBanList(BanList.Type.PROFILE);
        BanList nameBans = Bukkit.getBanList(BanList.Type.NAME);
        profileBans.pardon(player.getPlayerProfile());
        nameBans.pardon(identity.nick());
    }

    private PlayerIdentity identity(BanEntry<?> entry) {
        Object target = entry.getBanTarget();
        if (target instanceof PlayerProfile profile && profile.getUniqueId() != null) {
            String name = profile.getName();
            if (name == null || name.isBlank()) name = store.knownNick(profile.getUniqueId());
            if (name == null || name.isBlank()) name = profile.getUniqueId().toString();
            return new PlayerIdentity(profile.getUniqueId(), name);
        }
        String name = entry.getTarget();
        if (name == null || name.isBlank()) return null;
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && player.getName().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return new PlayerIdentity(player.getUniqueId(), player.getName());
            }
        }
        plugin.getLogger().warning("Ban antigo de " + name + " mantido no sistema original, mas sem UUID conhecido para sincronizar.");
        return null;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record PlayerIdentity(UUID uuid, String nick) {}
}
