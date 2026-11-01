package me.zetra.zetraadmin.analytics;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import me.zetra.zetraadmin.ZetraAdmin;
import me.zetra.zetraadmin.moderation.ModerationStore;
import me.zetra.zetraadmin.moderation.PanelSyncService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class AnalyticsService implements Listener {
    private static final List<String> DEFAULT_COMMANDS = List.of(
        "spawn", "mercado", "ah", "clan", "daily", "loginbonus", "missions"
    );

    private final ZetraAdmin plugin;
    private final ModerationStore store;
    private final PanelSyncService panelSync;
    private final Map<UUID, ActiveSession> sessions = new ConcurrentHashMap<>();
    private final ZoneId zoneId;
    private final int minSessionSeconds;
    private final boolean enabled;
    private final boolean trackCommands;
    private final Set<String> commandWhitelist;
    private final Map<String, String> commandEvents;
    private final PlatformDetector platformDetector;
    private BukkitTask ccuTask;

    public AnalyticsService(ZetraAdmin plugin, ModerationStore store, PanelSyncService panelSync) {
        this.plugin = plugin;
        this.store = store;
        this.panelSync = panelSync;
        this.enabled = plugin.getConfig().getBoolean("analytics.enabled", true);
        this.zoneId = loadZone(plugin.getConfig().getString("analytics.timezone", "America/Sao_Paulo"));
        this.minSessionSeconds = Math.max(0, plugin.getConfig().getInt("analytics.min-session-seconds", 30));
        this.trackCommands = plugin.getConfig().getBoolean("analytics.track-commands.enabled", true);
        List<String> configuredCommands = plugin.getConfig().getStringList("analytics.track-commands.whitelist");
        if (configuredCommands.isEmpty()) configuredCommands = DEFAULT_COMMANDS;
        this.commandWhitelist = configuredCommands.stream()
            .map(value -> value.toLowerCase(Locale.ROOT).trim())
            .filter(value -> !value.isBlank())
            .collect(Collectors.toUnmodifiableSet());
        this.commandEvents = loadCommandEvents(plugin.getConfig().getConfigurationSection("analytics.command-events"));
        this.platformDetector = new PlatformDetector();
    }

    public void start() {
        if (!enabled) {
            plugin.getLogger().info("Analytics do ZetraAdmin desativado no config.yml.");
            return;
        }
        if (store.analyticsWasRunning()) {
            queueNow("analytics_event", eventData(null, "possible_crash_or_unexpected_shutdown", "server_lifecycle", 1, "Inicializacao anterior sem desligamento limpo"));
        }
        store.setAnalyticsRunning(true);
        queue("analytics_event", eventData(null, "plugin_enable", "server_lifecycle", 1, "ZetraAdmin Analytics habilitado"));
        queue("analytics_event", eventData(null, "server_start", "server_lifecycle", 1, "Servidor/plugin iniciou"));
        ZetraAdminAnalyticsAPI.bind(this);

        for (Player player : Bukkit.getOnlinePlayers()) startSession(player);
        long minutes = Math.max(1, plugin.getConfig().getLong("analytics.ccu-snapshot-minutes", 5));
        ccuTask = Bukkit.getScheduler().runTaskTimer(plugin, this::snapshotCcu, 20L, minutes * 60L * 20L);
        plugin.getLogger().info(
            "Analytics integrado ao painel: sessoes, retencao, CCU e plataforma. Floodgate="
                + platformDetector.floodgateAvailable()
        );
    }

    public void stop() {
        if (!enabled) return;
        if (ccuTask != null) ccuTask.cancel();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, ActiveSession> entry : sessions.entrySet()) {
            finishSession(entry.getKey(), entry.getValue(), now, true);
        }
        sessions.clear();
        queueNow("analytics_event", eventData(null, "plugin_disable", "server_lifecycle", 1, "ZetraAdmin Analytics desabilitado"));
        queueNow("analytics_event", eventData(null, "server_stop", "server_lifecycle", 1, "Servidor/plugin desligou"));
        store.setAnalyticsRunning(false);
        ZetraAdminAnalyticsAPI.bind(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (enabled) startSession(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!enabled) return;
        ActiveSession session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) finishSession(event.getPlayer().getUniqueId(), session, System.currentTimeMillis(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!enabled || !trackCommands) return;
        String raw = event.getMessage().replaceFirst("^/", "").trim();
        if (raw.isBlank()) return;
        String command = raw.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!commandWhitelist.isEmpty() && !commandWhitelist.contains(command)) return;
        trackEvent(event.getPlayer(), "command_use", "command", 1, command);
        String mappedEvent = commandEvents.get(command);
        if (mappedEvent != null && !mappedEvent.isBlank()) {
            trackEvent(event.getPlayer(), mappedEvent, "funnel", 1, command);
        }
    }

    public void trackEvent(Player player, String eventType, String category, double value, String extra) {
        if (!enabled || player == null) return;
        queue("analytics_event", eventData(player, eventType, category, value, extra));
    }

    private void startSession(Player player) {
        long joinedAt = System.currentTimeMillis();
        String platform = platformDetector.detect(player);
        ActiveSession previous = sessions.put(player.getUniqueId(), new ActiveSession(player.getName(), platform, joinedAt));
        if (previous != null) finishSession(player.getUniqueId(), previous, joinedAt, true);
        Map<String, Object> data = playerData(player.getUniqueId(), player.getName(), platform);
        data.put("joinedAt", joinedAt);
        data.put("activityDate", localDate(joinedAt));
        queue("analytics_player_join", data);
    }

    private void finishSession(UUID uuid, ActiveSession session, long quitAt, boolean interrupted) {
        long duration = Math.max(0L, (quitAt - session.joinedAt()) / 1000L);
        if (duration < minSessionSeconds) return;
        Map<String, Object> data = playerData(uuid, session.name(), session.platform());
        data.put("joinedAt", session.joinedAt());
        data.put("quitAt", quitAt);
        data.put("durationSeconds", duration);
        data.put("activityDate", localDate(quitAt));
        data.put("interrupted", interrupted);
        if (interrupted) queueNow("analytics_session_end", data);
        else queue("analytics_session_end", data);
    }

    private void snapshotCcu() {
        int javaOnline = 0;
        int bedrockOnline = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (platformDetector.detect(player).equals("BEDROCK")) bedrockOnline++;
            else javaOnline++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalOnline", javaOnline + bedrockOnline);
        data.put("javaOnline", javaOnline);
        data.put("bedrockOnline", bedrockOnline);
        data.put("capturedAt", System.currentTimeMillis());
        queue("analytics_ccu_snapshot", data);
    }

    private Map<String, Object> eventData(Player player, String eventType, String category, double value, String extra) {
        Map<String, Object> data = player == null
            ? playerData(null, "server", "JAVA")
            : playerData(player.getUniqueId(), player.getName(), platformDetector.detect(player));
        data.put("eventType", safeKey(eventType, "custom"));
        data.put("category", safeKey(category, "custom"));
        data.put("value", Double.isFinite(value) ? value : 0D);
        data.put("extra", extra == null ? "" : extra.substring(0, Math.min(extra.length(), 2000)));
        data.put("occurredAt", System.currentTimeMillis());
        return data;
    }

    private Map<String, Object> playerData(UUID uuid, String nick, String platform) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uuid", uuid == null ? "" : uuid.toString());
        data.put("nick", nick == null ? "" : nick.substring(0, Math.min(nick.length(), 32)));
        data.put("platform", platform);
        return data;
    }

    private void queue(String type, Map<String, Object> data) {
        panelSync.queueAnalyticsEvent(type, data, System.currentTimeMillis());
    }

    private void queueNow(String type, Map<String, Object> data) {
        panelSync.queueAnalyticsEventNow(type, data, System.currentTimeMillis());
    }

    private String localDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().toString();
    }

    private String safeKey(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.trim().replaceAll("[^A-Za-z0-9_.:-]", "_");
        return clean.substring(0, Math.min(clean.length(), 80));
    }

    private ZoneId loadZone(String configured) {
        try {
            return ZoneId.of(configured == null ? "America/Sao_Paulo" : configured.trim());
        } catch (Exception ignored) {
            return ZoneId.of("America/Sao_Paulo");
        }
    }

    private Map<String, String> loadCommandEvents(ConfigurationSection section) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("daily", "daily_open");
        result.put("loginbonus", "daily_open");
        result.put("missions", "missions_open");
        if (section == null) return result;
        for (String key : section.getKeys(false)) {
            String value = section.getString(key, "").trim();
            if (!value.isBlank()) result.put(key.toLowerCase(Locale.ROOT), value);
        }
        return result;
    }

    private record ActiveSession(String name, String platform, long joinedAt) {
    }

    private static final class PlatformDetector {
        private final boolean floodgateAvailable;
        private final Object floodgateApi;
        private final Method isFloodgatePlayer;

        private PlatformDetector() {
            Object api = null;
            Method detector = null;
            Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
            boolean available = floodgate != null && floodgate.isEnabled();
            if (available) {
                try {
                    Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                    api = apiClass.getMethod("getInstance").invoke(null);
                    detector = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                } catch (ReflectiveOperationException ignored) {
                    available = false;
                }
            }
            this.floodgateAvailable = available;
            this.floodgateApi = api;
            this.isFloodgatePlayer = detector;
        }

        private String detect(Player player) {
            if (floodgateAvailable) {
                try {
                    if (Boolean.TRUE.equals(isFloodgatePlayer.invoke(floodgateApi, player.getUniqueId()))) return "BEDROCK";
                } catch (ReflectiveOperationException ignored) {
                }
            }
            return player.getName().startsWith(".") ? "BEDROCK" : "JAVA";
        }

        private boolean floodgateAvailable() {
            return floodgateAvailable;
        }
    }
}
