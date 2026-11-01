package me.zetra.zetraadmin.moderation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.zetra.zetraadmin.ZetraAdmin;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;

public final class PanelSyncService {
    private final ZetraAdmin plugin;
    private final ModerationStore store;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ZetraAdmin-PanelSync");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http;
    private final String endpoint;
    private final String token;
    private final String serverId;
    private final int batchSize;
    private final int timeoutMs;
    private final long fullSyncIntervalMs;
    private volatile boolean online;
    private volatile long lastFullSyncAt;
    private volatile long lastErrorLogAt;
    private volatile String lastErrorMessage = "";

    public PanelSyncService(ZetraAdmin plugin, ModerationStore store) {
        this.plugin = plugin;
        this.store = store;
        this.endpoint = trimSlash(plugin.getConfig().getString("panel-sync.endpoint", ""));
        this.token = plugin.getConfig().getString("panel-sync.api-token", "").trim();
        this.serverId = plugin.getConfig().getString("panel-sync.server-id", "semi-anarquia").trim();
        this.batchSize = Math.max(1, Math.min(100, plugin.getConfig().getInt("panel-sync.batch-size", 50)));
        this.timeoutMs = Math.max(1000, plugin.getConfig().getInt("panel-sync.timeout-ms", 5000));
        this.fullSyncIntervalMs = Math.max(15L, plugin.getConfig().getLong("panel-sync.full-sync-seconds", 60L)) * 1000L;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
    }

    public void start() {
        int interval = Math.max(3, plugin.getConfig().getInt("panel-sync.interval-seconds", 5));
        executor.scheduleWithFixedDelay(this::syncSafely, 1, interval, TimeUnit.SECONDS);
        if (enabled()) {
            plugin.getLogger().info("Sincronizacao segura com o painel habilitada para " + serverId + ".");
        } else {
            plugin.getLogger().warning(
                "Sincronizacao com painel desativada ou incompleta: enabled="
                    + plugin.getConfig().getBoolean("panel-sync.enabled", false)
                    + ", endpointHttps=" + endpoint.startsWith("https://")
                    + ", tokenConfigurado=" + !token.isBlank()
                    + ", serverIdConfigurado=" + !serverId.isBlank()
                    + ". Cache e reconciliacao local continuam ativos."
            );
        }
    }

    public void stop() {
        executor.shutdownNow();
        store.save();
    }

    public boolean isOnline() {
        return online;
    }

    public void recordLocalBan(UUID uuid, String nick, String reason, String actor, String origin, Instant expiresAt) {
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            long expiresAtMillis = expiresAt == null ? 0L : expiresAt.toEpochMilli();
            store.applyBan(UUID.randomUUID().toString(), uuid, nick, reason, actor, origin, now, expiresAtMillis, "", true);
            try {
                runMain(() -> kickOnlinePlayer(uuid, nick));
            } catch (Exception error) {
                plugin.getLogger().warning("Ban salvo, mas falhou a expulsao de " + nick + ": " + safe(error));
            }
            syncSafely();
        });
    }

    public void recordLocalUnban(UUID uuid, String nick, String actor, String origin) {
        executor.execute(() -> {
            try {
                BanRecord activeBan = store.activeBan(uuid, nick).orElse(null);
                clearExternalBanSafely(
                    activeBan == null ? uuid : activeBan.playerUuid(),
                    activeBan == null || activeBan.nick().isBlank() ? nick : activeBan.nick()
                );
                long now = System.currentTimeMillis();
                store.revokeBan(UUID.randomUUID().toString(), uuid, nick, "Unban manual", actor, origin, "", now, true);
                syncSafely();
            } catch (Exception error) {
                plugin.getLogger().warning("Nao foi possivel concluir o unban de " + nick + ": " + safe(error));
            }
        });
    }

    public void triggerSync() {
        if (enabled()) executor.execute(this::syncSafely);
    }

    public void queueAnalyticsEvent(String type, Map<String, Object> data, long createdAt) {
        executor.execute(() -> store.queueEvent(new SyncEvent(
            UUID.randomUUID().toString(), type, data, createdAt
        )));
    }

    public void queueAnalyticsEventNow(String type, Map<String, Object> data, long createdAt) {
        store.queueEvent(new SyncEvent(UUID.randomUUID().toString(), type, data, createdAt));
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("panel-sync.enabled", false)
            && endpoint.startsWith("https://")
            && !token.isBlank()
            && !serverId.isBlank();
    }

    private void syncSafely() {
        try {
            expireBans();
            if (!enabled()) {
                online = false;
                return;
            }
            // A failed analytics/outbox upload must not prevent receiving moderation commands.
            Exception uploadError = null;
            try { pushEvents(); } catch (Exception error) { uploadError = error; }
            pullCommands();
            // Never let a snapshot erase a local moderation action that has not reached the panel yet.
            if (uploadError == null && (lastFullSyncAt == 0L || System.currentTimeMillis() - lastFullSyncAt >= fullSyncIntervalMs)) {
                pullAuthoritativeSnapshot();
            }
            if (uploadError != null) throw uploadError;
            if (!online) plugin.getLogger().info("Conectado ao painel ZetraMC com sucesso.");
            online = true;
        } catch (Exception error) {
            String message = safe(error);
            long now = System.currentTimeMillis();
            if (!message.equals(lastErrorMessage) || now - lastErrorLogAt >= 60_000L) {
                plugin.getLogger().warning("Falha ao sincronizar com o painel; cache local permanece ativo: " + message);
                lastErrorMessage = message;
                lastErrorLogAt = now;
            }
            online = false;
        }
    }

    private void expireBans() {
        store.expireDueBans();
    }

    private void pushEvents() throws Exception {
        List<SyncEvent> events = store.pendingEvents(batchSize);
        if (events.isEmpty()) return;
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        body.add("events", gson.toJsonTree(events));
        JsonObject response = post("/events", body);
        List<String> accepted = new ArrayList<>();
        JsonArray ids = response.has("acceptedEventIds") ? response.getAsJsonArray("acceptedEventIds") : new JsonArray();
        for (JsonElement id : ids) accepted.add(id.getAsString());
        store.acknowledgeEvents(accepted);
    }

    private void pullCommands() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        body.addProperty("limit", batchSize);
        JsonObject response = post("/pull", body);
        JsonArray commands = response.has("commands") ? response.getAsJsonArray("commands") : new JsonArray();
        for (JsonElement element : commands) executeCommand(element.getAsJsonObject());
        if (!commands.isEmpty()) pushEvents();
    }

    private void pullAuthoritativeSnapshot() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        JsonObject response = post("/snapshot", body);
        if (!response.has("authoritative") || !response.get("authoritative").getAsBoolean()) {
            throw new IllegalStateException("snapshot_not_authoritative");
        }
        JsonArray bans = response.has("bans") ? response.getAsJsonArray("bans") : new JsonArray();
        int declaredTotal = response.has("total") ? response.get("total").getAsInt() : -1;
        if (declaredTotal < 0 || declaredTotal != bans.size()) throw new IllegalStateException("snapshot_total_mismatch");

        long snapshotAt = response.has("generatedAtEpoch")
            ? Math.max(1L, response.get("generatedAtEpoch").getAsLong())
            : System.currentTimeMillis();
        List<BanRecord> records = new ArrayList<>();
        java.util.HashSet<UUID> unique = new java.util.HashSet<>();
        for (JsonElement element : bans) {
            JsonObject ban = element.getAsJsonObject();
            UUID uuid = UUID.fromString(text(ban, "uuid"));
            String nick = text(ban, "nick");
            if (nick.isBlank() || !unique.add(uuid)) throw new IllegalStateException("snapshot_invalid_or_duplicate_ban");
            long createdAt = number(ban, "createdAtEpoch");
            long expiresAt = number(ban, "expiresAtEpoch");
            records.add(new BanRecord(
                text(ban, "eventId"), uuid, nick, text(ban, "reason"), text(ban, "actor"),
                text(ban, "origin"), createdAt, expiresAt, true
            ));
        }

        boolean firstSync = lastFullSyncAt == 0L;
        ModerationStore.ReconcileResult result = store.reconcileAuthoritativeBans(records, snapshotAt);
        lastFullSyncAt = System.currentTimeMillis();
        if (result.before() != result.after() || firstSync) {
            plugin.getLogger().info("Snapshot SQL reconciliado: " + result.after() + " bans ativos.");
        }
        runMain(() -> plugin.getServer().getOnlinePlayers().forEach(plugin.getBanListener()::enforce));
    }

    private void executeCommand(JsonObject command) {
        String commandId = text(command, "id");
        if (commandId.isBlank()) return;
        if (store.commandApplied(commandId)) {
            store.queueCommandDuplicate(UUID.randomUUID().toString(), commandId);
            return;
        }
        try {
            switch (text(command, "type")) {
                case "resolve_player" -> resolvePlayer(commandId, text(command, "nick"));
                case "ban" -> applyPanelBan(commandId, command);
                case "unban" -> applyPanelUnban(commandId, command);
                default -> store.queueCommandFailure(UUID.randomUUID().toString(), commandId, "unsupported_command");
            }
        } catch (Exception error) {
            plugin.getLogger().warning("Comando do painel " + commandId + " falhou: " + safe(error));
            store.queueCommandFailure(UUID.randomUUID().toString(), commandId, safe(error));
        }
    }

    private void resolvePlayer(String commandId, String requestedNick) throws Exception {
        Optional<OfflinePlayer> player = runMainWithResult(() -> findPlayer(requestedNick));
        if (player.isEmpty()) {
            store.queuePlayerResolved(UUID.randomUUID().toString(), commandId, null, requestedNick, false);
            return;
        }
        OfflinePlayer found = player.get();
        String nick = found.getName() == null ? requestedNick : found.getName();
        store.queuePlayerResolved(UUID.randomUUID().toString(), commandId, found.getUniqueId(), nick, true);
    }

    private void applyPanelBan(String commandId, JsonObject command) throws Exception {
        UUID uuid = UUID.fromString(text(command, "uuid"));
        String nick = text(command, "nick");
        String reason = text(command, "reason");
        String actor = text(command, "requestedBy");
        long durationSeconds = number(command, "durationSeconds");
        long commandVersion = number(command, "createdAtEpoch");
        if (commandVersion > 0L && commandVersion < store.latestStateVersion(uuid)) {
            store.queueCommandFailure(UUID.randomUUID().toString(), commandId, "conflict_newer_local_action");
            return;
        }
        long now = commandVersion > 0L ? commandVersion : System.currentTimeMillis();
        long expiresAt = durationSeconds > 0 ? now + durationSeconds * 1000L : 0L;
        String eventId = UUID.randomUUID().toString();
        store.applyBan(eventId, uuid, nick, reason, actor, "panel", now, expiresAt, commandId, true);
        runMain(() -> kickOnlinePlayer(uuid, nick));
        plugin.getLogger().info("Ban do painel processado para " + nick + " (comando " + commandId + ").");
    }

    private void applyPanelUnban(String commandId, JsonObject command) throws Exception {
        UUID uuid = UUID.fromString(text(command, "uuid"));
        String nick = text(command, "nick");
        String actor = text(command, "requestedBy");
        String reason = text(command, "reason");
        long commandVersion = number(command, "createdAtEpoch");
        if (commandVersion > 0L && commandVersion < store.latestStateVersion(uuid)) {
            store.queueCommandFailure(UUID.randomUUID().toString(), commandId, "conflict_newer_local_action");
            return;
        }
        long occurredAt = commandVersion > 0L ? commandVersion : System.currentTimeMillis();
        BanRecord activeBan = store.activeBan(uuid, nick).orElse(null);
        clearExternalBanSafely(
            activeBan == null ? uuid : activeBan.playerUuid(),
            activeBan == null || activeBan.nick().isBlank() ? nick : activeBan.nick()
        );
        store.revokeBan(UUID.randomUUID().toString(), uuid, nick, reason, actor, "panel", commandId, occurredAt, true);
    }

    public Optional<OfflinePlayer> findKnownPlayer(String nick) {
        if (nick == null || nick.isBlank()) return Optional.empty();
        Player onlinePlayer = Bukkit.getPlayerExact(nick);
        if (onlinePlayer != null) return Optional.of(onlinePlayer);
        Optional<UUID> known = store.findKnownUuid(nick);
        if (known.isPresent()) return Optional.of(Bukkit.getOfflinePlayer(known.get()));
        try {
            Object cached = Bukkit.class.getMethod("getOfflinePlayerIfCached", String.class).invoke(null, nick);
            if (cached instanceof OfflinePlayer player) return Optional.of(player);
        } catch (ReflectiveOperationException ignored) {
            // Spigot puro nao oferece esta API; nesse caso usamos apenas o cache proprio.
        }
        return Optional.empty();
    }

    private Optional<OfflinePlayer> findPlayer(String nick) {
        return findKnownPlayer(nick);
    }

    private void kickOnlinePlayer(UUID uuid, String nick) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer == null && nick != null && !nick.isBlank()) onlinePlayer = Bukkit.getPlayerExact(nick);
        if (onlinePlayer != null && !plugin.getBanListener().enforce(onlinePlayer)) {
            throw new IllegalStateException("ban_saved_but_kick_cancelled_by_plugin");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void clearExternalBanSafely(UUID uuid, String nick) throws Exception {
        if (!plugin.getConfig().getBoolean("compatibility.clear-legacy-ban-on-unban", true)) return;
        runMain(() -> {
            BanList<PlayerProfile> profileBans = Bukkit.getBanList(BanList.Type.PROFILE);
            profileBans.pardon(Bukkit.createPlayerProfile(uuid));
            if (nick != null && !nick.isBlank()) Bukkit.getBanList(BanList.Type.NAME).pardon(nick);
        });
    }

    private JsonObject post(String path, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + path))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private void runMain(Runnable action) throws Exception {
        runMainWithResult(() -> {
            action.run();
            return Boolean.TRUE;
        });
    }

    private <T> T runMainWithResult(java.util.concurrent.Callable<T> action) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(action.call());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future.get(Math.max(5_000L, timeoutMs * 2L), TimeUnit.MILLISECONDS);
    }

    private String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString().trim() : "";
    }

    private long number(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? Math.max(0L, object.get(key).getAsLong()) : 0L;
    }

    private String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String safe(Throwable error) {
        String value = error.getMessage();
        String result = value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
        return token.isBlank() ? result : result.replace(token, "[redacted]");
    }
}
