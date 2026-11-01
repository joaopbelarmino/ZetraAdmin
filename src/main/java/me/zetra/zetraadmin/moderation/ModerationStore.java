package me.zetra.zetraadmin.moderation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.zetra.zetraadmin.ZetraAdmin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ModerationStore implements AutoCloseable {
    private static final Type EVENT_DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();

    private final ZetraAdmin plugin;
    private final File databaseFile;
    private final File legacyFile;
    private final Gson gson = new Gson();
    private Connection connection;

    public ModerationStore(ZetraAdmin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "moderation.db");
        this.legacyFile = new File(plugin.getDataFolder(), "moderation-cache.yml");
    }

    public synchronized void load() {
        close();
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Nao foi possivel criar a pasta do ZetraAdmin");
        }
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=5000");
            }
            createSchema();
            migrateLegacyYaml();
        } catch (ClassNotFoundException | SQLException error) {
            close();
            throw new IllegalStateException("Falha ao abrir o banco SQL local de moderacao", error);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS moderation_players (
                  uuid TEXT PRIMARY KEY,
                  nick TEXT NOT NULL,
                  name_key TEXT NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS moderation_players_name_idx ON moderation_players(name_key, updated_at)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS moderation_bans (
                  uuid TEXT PRIMARY KEY,
                  event_id TEXT NOT NULL DEFAULT '',
                  nick TEXT NOT NULL,
                  name_key TEXT NOT NULL,
                  reason TEXT NOT NULL DEFAULT '',
                  actor TEXT NOT NULL DEFAULT '',
                  origin TEXT NOT NULL DEFAULT '',
                  created_at INTEGER NOT NULL DEFAULT 0,
                  expires_at INTEGER NOT NULL DEFAULT 0,
                  active INTEGER NOT NULL DEFAULT 0,
                  state_version_at INTEGER NOT NULL DEFAULT 0,
                  revoked_at INTEGER NOT NULL DEFAULT 0,
                  revoked_by TEXT NOT NULL DEFAULT '',
                  revoke_reason TEXT NOT NULL DEFAULT '',
                  authoritative_seen INTEGER NOT NULL DEFAULT 0
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS moderation_bans_active_name_idx ON moderation_bans(active, name_key)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS moderation_bans_expiry_idx ON moderation_bans(active, expires_at)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS moderation_outbox (
                  event_id TEXT PRIMARY KEY,
                  event_type TEXT NOT NULL,
                  payload_json TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS moderation_outbox_created_idx ON moderation_outbox(created_at, event_id)");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS moderation_applied_commands (
                  command_id TEXT PRIMARY KEY,
                  applied_at INTEGER NOT NULL,
                  failure_detail TEXT NOT NULL DEFAULT ''
                )
                """);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS moderation_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
        }
    }

    private void migrateLegacyYaml() throws SQLException {
        if (meta("legacy_yaml_migrated").isPresent() || !legacyFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(legacyFile);
        inTransaction(() -> {
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players != null) {
                for (String uuid : players.getKeys(false)) {
                    String nick = yaml.getString("players." + uuid + ".nick", "");
                    try {
                        if (!nick.isBlank()) upsertPlayer(UUID.fromString(uuid), nick, yaml.getLong("players." + uuid + ".updated-at", 0L));
                    } catch (IllegalArgumentException ignored) {
                        plugin.getLogger().warning("UUID invalido ignorado durante migracao SQL: " + uuid);
                    }
                }
            }
            ConfigurationSection bans = yaml.getConfigurationSection("bans");
            if (bans != null) {
                for (String uuidText : bans.getKeys(false)) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidText);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    String path = "bans." + uuidText;
                    upsertBan(
                        new BanRecord(
                            yaml.getString(path + ".event-id", ""), uuid,
                            yaml.getString(path + ".nick", ""),
                            yaml.getString(path + ".reason", "Nao informado"),
                            yaml.getString(path + ".actor", "Sistema"),
                            yaml.getString(path + ".origin", "legacy_yaml"),
                            yaml.getLong(path + ".created-at", 0L),
                            yaml.getLong(path + ".expires-at", 0L),
                            yaml.getBoolean(path + ".active", false)
                        ),
                        yaml.getLong(path + ".state-version-at", yaml.getLong(path + ".created-at", 0L)),
                        yaml.getLong(path + ".revoked-at", 0L),
                        yaml.getString(path + ".revoked-by", ""),
                        yaml.getString(path + ".revoke-reason", ""), false
                    );
                }
            }
            ConfigurationSection outbox = yaml.getConfigurationSection("outbox");
            if (outbox != null) {
                for (String eventId : outbox.getKeys(false)) {
                    String path = "outbox." + eventId;
                    ConfigurationSection payload = yaml.getConfigurationSection(path + ".data");
                    queueEventWithoutCommit(new SyncEvent(eventId, yaml.getString(path + ".type", ""),
                        payload == null ? Map.of() : payload.getValues(false), yaml.getLong(path + ".created-at", 0L)));
                }
            }
            ConfigurationSection commands = yaml.getConfigurationSection("applied-commands");
            if (commands != null) {
                for (String commandId : commands.getKeys(false)) {
                    markCommandWithoutCommit(commandId, yaml.getLong("applied-commands." + commandId, System.currentTimeMillis()),
                        yaml.getString("command-failures." + commandId, ""));
                }
            }
            setMeta("analytics_running", Boolean.toString(yaml.getBoolean("analytics-state.running", false)));
            setMeta("legacy_yaml_migrated", Long.toString(System.currentTimeMillis()));
        });

        File backup = new File(plugin.getDataFolder(), "moderation-cache.migrated-backup.yml");
        if (!backup.exists()) {
            try {
                Files.copy(legacyFile.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException error) {
                plugin.getLogger().warning("Banco SQL criado, mas nao foi possivel copiar o backup YAML: " + error.getMessage());
            }
        }
        plugin.getLogger().info("Cache YAML migrado para moderation.db com sucesso.");
    }

    public synchronized void rememberPlayer(UUID uuid, String nick) {
        if (uuid == null || nick == null || nick.isBlank()) return;
        try {
            upsertPlayer(uuid, nick, System.currentTimeMillis());
        } catch (SQLException error) {
            throw databaseFailure("salvar jogador", error);
        }
    }

    public synchronized Optional<UUID> findKnownUuid(String nick) {
        if (nick == null || nick.isBlank()) return Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT uuid FROM moderation_players WHERE name_key = ? ORDER BY updated_at DESC LIMIT 1")) {
            statement.setString(1, nameKey(nick));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(UUID.fromString(result.getString(1))) : Optional.empty();
            }
        } catch (SQLException | IllegalArgumentException error) {
            throw databaseFailure("consultar jogador", error);
        }
    }

    public synchronized String knownNick(UUID uuid) {
        if (uuid == null) return "";
        try (PreparedStatement statement = connection.prepareStatement("SELECT nick FROM moderation_players WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : "";
            }
        } catch (SQLException error) {
            throw databaseFailure("consultar nick", error);
        }
    }

    public synchronized BanRecord applyBan(String eventId, UUID uuid, String nick, String reason, String actor,
                                             String origin, long createdAt, long expiresAt, String commandId,
                                             boolean queueForPanel) {
        BanRecord record = new BanRecord(eventId, uuid, nick, reason, actor, origin, createdAt, expiresAt, true);
        try {
            inTransaction(() -> {
                upsertPlayer(uuid, nick, createdAt);
                upsertBan(record, createdAt, 0L, "", "", false);
                if (commandId != null && !commandId.isBlank()) markCommandWithoutCommit(commandId, System.currentTimeMillis(), "");
                if (queueForPanel) queueEventWithoutCommit(new SyncEvent(eventId, "ban_applied", banEventData(record, commandId), createdAt));
            });
            return record;
        } catch (SQLException error) {
            throw databaseFailure("aplicar ban", error);
        }
    }

    public synchronized void revokeBan(String eventId, UUID uuid, String nick, String reason, String actor,
                                        String origin, String commandId, long occurredAt, boolean queueForPanel) {
        try {
            UUID storedUuid = activeBanUuid(uuid, nick).orElse(uuid);
            inTransaction(() -> {
                upsertPlayer(storedUuid, nick, occurredAt);
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO moderation_bans
                      (uuid, event_id, nick, name_key, reason, actor, origin, created_at, expires_at, active,
                       state_version_at, revoked_at, revoked_by, revoke_reason, authoritative_seen)
                    VALUES (?, '', ?, ?, '', '', ?, 0, 0, 0, ?, ?, ?, ?, 0)
                    ON CONFLICT(uuid) DO UPDATE SET active = 0, state_version_at = excluded.state_version_at,
                      revoked_at = excluded.revoked_at, revoked_by = excluded.revoked_by,
                      revoke_reason = excluded.revoke_reason, authoritative_seen = 0
                    """)) {
                    statement.setString(1, storedUuid.toString());
                    statement.setString(2, nick);
                    statement.setString(3, nameKey(nick));
                    statement.setString(4, origin);
                    statement.setLong(5, occurredAt);
                    statement.setLong(6, occurredAt);
                    statement.setString(7, actor);
                    statement.setString(8, reason);
                    statement.executeUpdate();
                }
                if (commandId != null && !commandId.isBlank()) markCommandWithoutCommit(commandId, System.currentTimeMillis(), "");
                if (queueForPanel) {
                    Map<String, Object> payload = basePlayerData(storedUuid, nick);
                    payload.put("reason", reason);
                    payload.put("actor", actor);
                    payload.put("origin", origin);
                    payload.put("commandId", commandId == null ? "" : commandId);
                    payload.put("createdAt", occurredAt);
                    queueEventWithoutCommit(new SyncEvent(eventId, "ban_revoked", payload, occurredAt));
                }
            });
        } catch (SQLException error) {
            throw databaseFailure("revogar ban", error);
        }
    }

    public synchronized Optional<BanRecord> activeBan(UUID uuid, String nick) {
        try {
            return activeBanUuid(uuid, nick).flatMap(this::readBanUnchecked);
        } catch (SQLException error) {
            throw databaseFailure("consultar ban", error);
        }
    }

    public synchronized long latestStateVersion(UUID uuid) {
        if (uuid == null) return 0L;
        try (PreparedStatement statement = connection.prepareStatement("SELECT state_version_at FROM moderation_bans WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException error) {
            throw databaseFailure("consultar versao do ban", error);
        }
    }

    public synchronized List<BanRecord> activeBans() {
        List<BanRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM moderation_bans WHERE active = 1 AND (expires_at = 0 OR expires_at > ?) ORDER BY nick COLLATE NOCASE")) {
            statement.setLong(1, System.currentTimeMillis());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readBan(rows));
            }
            return result;
        } catch (SQLException error) {
            throw databaseFailure("listar bans", error);
        }
    }

    public synchronized ReconcileResult reconcileAuthoritativeBans(Collection<BanRecord> authoritative, long snapshotAt) {
        Map<UUID, BanRecord> unique = new LinkedHashMap<>();
        for (BanRecord record : authoritative) {
            if (record != null && record.playerUuid() != null && record.active()) unique.put(record.playerUuid(), record);
        }
        try {
            int before = activeBans().size();
            inTransaction(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE moderation_bans SET authoritative_seen = 0");
                }
                for (BanRecord record : unique.values()) {
                    upsertPlayer(record.playerUuid(), record.nick(), snapshotAt);
                    upsertBan(record, Math.max(record.createdAt(), snapshotAt), 0L, "", "", true);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE moderation_bans
                       SET active = 0, state_version_at = ?, revoked_at = ?, revoked_by = 'Painel ZetraMC',
                           revoke_reason = 'Ausente no snapshot SQL autoritativo'
                     WHERE active = 1 AND authoritative_seen = 0
                    """)) {
                    statement.setLong(1, snapshotAt);
                    statement.setLong(2, snapshotAt);
                    statement.executeUpdate();
                }
                setMeta("last_authoritative_snapshot", Long.toString(snapshotAt));
                setMeta("last_authoritative_total", Integer.toString(unique.size()));
            });
            int after = activeBans().size();
            return new ReconcileResult(before, after, unique.size());
        } catch (SQLException error) {
            throw databaseFailure("reconciliar snapshot", error);
        }
    }

    public synchronized List<BanRecord> expireDueBans() {
        long now = System.currentTimeMillis();
        List<BanRecord> expired = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM moderation_bans WHERE active = 1 AND expires_at > 0 AND expires_at <= ?")) {
            statement.setLong(1, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) expired.add(readBan(rows));
            }
            if (expired.isEmpty()) return expired;
            inTransaction(() -> {
                for (BanRecord record : expired) {
                    try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE moderation_bans SET active = 0, state_version_at = ?, revoked_at = ?, revoked_by = 'Sistema', revoke_reason = 'Banimento temporario expirado', authoritative_seen = 0 WHERE uuid = ?")) {
                        update.setLong(1, now);
                        update.setLong(2, now);
                        update.setString(3, record.playerUuid().toString());
                        update.executeUpdate();
                    }
                    String eventId = UUID.randomUUID().toString();
                    Map<String, Object> payload = basePlayerData(record.playerUuid(), record.nick());
                    payload.put("reason", "Banimento temporario expirado");
                    payload.put("actor", "Sistema");
                    payload.put("origin", "plugin_expiration");
                    queueEventWithoutCommit(new SyncEvent(eventId, "ban_expired", payload, now));
                }
            });
            return expired;
        } catch (SQLException error) {
            throw databaseFailure("expirar bans", error);
        }
    }

    public synchronized void queuePlayerResolved(String eventId, String commandId, UUID uuid, String nick, boolean known) {
        try {
            inTransaction(() -> {
                if (known) upsertPlayer(uuid, nick, System.currentTimeMillis());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("commandId", commandId);
                payload.put("known", known);
                payload.put("uuid", uuid == null ? "" : uuid.toString());
                payload.put("nick", nick == null ? "" : nick);
                queueEventWithoutCommit(new SyncEvent(eventId, "player_resolved", payload, System.currentTimeMillis()));
                markCommandWithoutCommit(commandId, System.currentTimeMillis(), "");
            });
        } catch (SQLException error) {
            throw databaseFailure("registrar jogador resolvido", error);
        }
    }

    public synchronized void queueCommandFailure(String eventId, String commandId, String detail) {
        try {
            inTransaction(() -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("commandId", commandId);
                payload.put("detail", detail);
                queueEventWithoutCommit(new SyncEvent(eventId, "command_failed", payload, System.currentTimeMillis()));
                markCommandWithoutCommit(commandId, System.currentTimeMillis(), detail);
            });
        } catch (SQLException error) {
            throw databaseFailure("registrar falha de comando", error);
        }
    }

    public synchronized void queueCommandDuplicate(String eventId, String commandId) {
        try {
            String failure = commandFailure(commandId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("commandId", commandId);
            payload.put("detail", failure.isBlank() ? "Comando ja aplicado localmente" : failure);
            queueEvent(new SyncEvent(eventId, failure.isBlank() ? "command_duplicate" : "command_failed", payload, System.currentTimeMillis()));
        } catch (SQLException error) {
            throw databaseFailure("registrar comando duplicado", error);
        }
    }

    public synchronized void queueEvent(SyncEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) return;
        try {
            queueEventWithoutCommit(event);
        } catch (SQLException error) {
            throw databaseFailure("enfileirar evento", error);
        }
    }

    public synchronized boolean analyticsWasRunning() {
        return Boolean.parseBoolean(meta("analytics_running").orElse("false"));
    }

    public synchronized void setAnalyticsRunning(boolean running) {
        try {
            setMeta("analytics_running", Boolean.toString(running));
            setMeta("analytics_updated_at", Long.toString(System.currentTimeMillis()));
        } catch (SQLException error) {
            throw databaseFailure("salvar estado dos analytics", error);
        }
    }

    public synchronized List<SyncEvent> pendingEvents(int limit) {
        List<SyncEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT event_id, event_type, payload_json, created_at FROM moderation_outbox ORDER BY created_at, event_id LIMIT ?")) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> data = gson.fromJson(rows.getString("payload_json"), EVENT_DATA_TYPE);
                    events.add(new SyncEvent(rows.getString("event_id"), rows.getString("event_type"), data, rows.getLong("created_at")));
                }
            }
            return events;
        } catch (SQLException error) {
            throw databaseFailure("ler fila de eventos", error);
        }
    }

    public synchronized void acknowledgeEvents(List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) return;
        try {
            inTransaction(() -> {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM moderation_outbox WHERE event_id = ?")) {
                    for (String eventId : eventIds) {
                        statement.setString(1, eventId);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            });
        } catch (SQLException error) {
            throw databaseFailure("confirmar eventos", error);
        }
    }

    public synchronized boolean commandApplied(String commandId) {
        if (commandId == null || commandId.isBlank()) return false;
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM moderation_applied_commands WHERE command_id = ?")) {
            statement.setString(1, commandId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException error) {
            throw databaseFailure("consultar comando aplicado", error);
        }
    }

    public synchronized void markCommandApplied(String commandId) {
        try {
            markCommandWithoutCommit(commandId, System.currentTimeMillis(), "");
        } catch (SQLException error) {
            throw databaseFailure("marcar comando aplicado", error);
        }
    }

    public synchronized void save() {
        if (connection == null) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(PASSIVE)");
        } catch (SQLException error) {
            plugin.getLogger().warning("Falha no checkpoint do banco de moderacao: " + error.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException error) {
            plugin.getLogger().warning("Falha ao fechar o banco de moderacao: " + error.getMessage());
        } finally {
            connection = null;
        }
    }

    private void upsertPlayer(UUID uuid, String nick, long updatedAt) throws SQLException {
        if (uuid == null || nick == null || nick.isBlank()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO moderation_players (uuid, nick, name_key, updated_at) VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET nick = excluded.nick, name_key = excluded.name_key, updated_at = excluded.updated_at
            """)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, nick);
            statement.setString(3, nameKey(nick));
            statement.setLong(4, Math.max(0L, updatedAt));
            statement.executeUpdate();
        }
    }

    private void upsertBan(BanRecord record, long stateVersion, long revokedAt, String revokedBy,
                           String revokeReason, boolean authoritativeSeen) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO moderation_bans
              (uuid, event_id, nick, name_key, reason, actor, origin, created_at, expires_at, active,
               state_version_at, revoked_at, revoked_by, revoke_reason, authoritative_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
              event_id = excluded.event_id, nick = excluded.nick, name_key = excluded.name_key,
              reason = excluded.reason, actor = excluded.actor, origin = excluded.origin,
              created_at = excluded.created_at, expires_at = excluded.expires_at, active = excluded.active,
              state_version_at = excluded.state_version_at, revoked_at = excluded.revoked_at,
              revoked_by = excluded.revoked_by, revoke_reason = excluded.revoke_reason,
              authoritative_seen = excluded.authoritative_seen
            """)) {
            statement.setString(1, record.playerUuid().toString());
            statement.setString(2, safe(record.eventId()));
            statement.setString(3, safe(record.nick()));
            statement.setString(4, nameKey(record.nick()));
            statement.setString(5, safe(record.reason()));
            statement.setString(6, safe(record.actor()));
            statement.setString(7, safe(record.origin()));
            statement.setLong(8, record.createdAt());
            statement.setLong(9, record.expiresAt());
            statement.setInt(10, record.active() ? 1 : 0);
            statement.setLong(11, stateVersion);
            statement.setLong(12, revokedAt);
            statement.setString(13, safe(revokedBy));
            statement.setString(14, safe(revokeReason));
            statement.setInt(15, authoritativeSeen ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private Optional<UUID> activeBanUuid(UUID uuid, String nick) throws SQLException {
        long now = System.currentTimeMillis();
        if (uuid != null && isActiveBan(uuid, now)) return Optional.of(uuid);
        if (nick == null || nick.isBlank()) return Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT uuid FROM moderation_bans WHERE name_key = ? AND active = 1 AND (expires_at = 0 OR expires_at > ?) ORDER BY state_version_at DESC LIMIT 1")) {
            statement.setString(1, nameKey(nick));
            statement.setLong(2, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(UUID.fromString(result.getString(1))) : Optional.empty();
            }
        }
    }

    private boolean isActiveBan(UUID uuid, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM moderation_bans WHERE uuid = ? AND active = 1 AND (expires_at = 0 OR expires_at > ?)")) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private Optional<BanRecord> readBanUnchecked(UUID uuid) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM moderation_bans WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readBan(result)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw databaseFailure("ler ban", error);
        }
    }

    private BanRecord readBan(ResultSet result) throws SQLException {
        return new BanRecord(result.getString("event_id"), UUID.fromString(result.getString("uuid")),
            result.getString("nick"), result.getString("reason"), result.getString("actor"),
            result.getString("origin"), result.getLong("created_at"), result.getLong("expires_at"),
            result.getInt("active") == 1);
    }

    private Map<String, Object> banEventData(BanRecord record, String commandId) {
        Map<String, Object> payload = basePlayerData(record.playerUuid(), record.nick());
        payload.put("reason", record.reason());
        payload.put("actor", record.actor());
        payload.put("origin", record.origin());
        payload.put("createdAt", record.createdAt());
        payload.put("expiresAt", record.expiresAt());
        payload.put("commandId", commandId == null ? "" : commandId);
        return payload;
    }

    private Map<String, Object> basePlayerData(UUID uuid, String nick) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uuid", uuid == null ? "" : uuid.toString());
        payload.put("nick", nick == null ? "" : nick);
        return payload;
    }

    private void queueEventWithoutCommit(SyncEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT OR IGNORE INTO moderation_outbox (event_id, event_type, payload_json, created_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, event.eventId());
            statement.setString(2, event.type());
            statement.setString(3, gson.toJson(event.data()));
            statement.setLong(4, event.createdAt());
            statement.executeUpdate();
        }
    }

    private void markCommandWithoutCommit(String commandId, long appliedAt, String failure) throws SQLException {
        if (commandId == null || commandId.isBlank()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO moderation_applied_commands (command_id, applied_at, failure_detail) VALUES (?, ?, ?)
            ON CONFLICT(command_id) DO UPDATE SET applied_at = excluded.applied_at,
              failure_detail = CASE WHEN excluded.failure_detail = '' THEN moderation_applied_commands.failure_detail ELSE excluded.failure_detail END
            """)) {
            statement.setString(1, commandId);
            statement.setLong(2, appliedAt);
            statement.setString(3, safe(failure));
            statement.executeUpdate();
        }
    }

    private String commandFailure(String commandId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT failure_detail FROM moderation_applied_commands WHERE command_id = ?")) {
            statement.setString(1, commandId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : "";
            }
        }
    }

    private Optional<String> meta(String key) {
        if (connection == null) return Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement("SELECT meta_value FROM moderation_meta WHERE meta_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw databaseFailure("ler metadado", error);
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO moderation_meta (meta_key, meta_value) VALUES (?, ?) ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private void inTransaction(SqlAction action) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            action.run();
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private String nameKey(String nick) {
        return safe(nick).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private IllegalStateException databaseFailure(String action, Exception error) {
        return new IllegalStateException("Falha SQL ao " + action + ": " + error.getMessage(), error);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    public record ReconcileResult(int before, int after, int authoritativeTotal) {}
}
