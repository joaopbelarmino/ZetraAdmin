package me.zetra.zetraadmin;

import java.util.Objects;
import me.zetra.zetraadmin.analytics.AnalyticsService;
import me.zetra.zetraadmin.commands.AdminCommand;
import me.zetra.zetraadmin.managers.LimitManager;
import me.zetra.zetraadmin.managers.LogManager;
import me.zetra.zetraadmin.moderation.BanListener;
import me.zetra.zetraadmin.moderation.LegacyBanMigrator;
import me.zetra.zetraadmin.moderation.ModerationStore;
import me.zetra.zetraadmin.moderation.PanelSyncService;
import me.zetra.zetraadmin.moderation.SafeUnbanRedirectListener;
import org.bukkit.plugin.java.JavaPlugin;

public class ZetraAdmin extends JavaPlugin {
    private static ZetraAdmin instance;
    private LimitManager limitManager;
    private LogManager logManager;
    private ModerationStore moderationStore;
    private PanelSyncService panelSyncService;
    private AnalyticsService analyticsService;
    private BanListener banListener;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        limitManager = new LimitManager(this);
        limitManager.loadLimits();
        logManager = new LogManager(this);

        moderationStore = new ModerationStore(this);
        try {
            moderationStore.load();
        } catch (RuntimeException error) {
            getLogger().severe("BANIMENTO INDISPONIVEL: banco SQL local nao iniciou. O plugin sera desativado para evitar operacao sem protecao.");
            error.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getConfig().getBoolean("legacy-ban-migration.enabled", false)) {
            getLogger().warning("Migracao unica de bans vanilla habilitada; ela sera executada apenas nesta inicializacao.");
            new LegacyBanMigrator(this, moderationStore).migrate();
        }
        banListener = new BanListener(this, moderationStore);
        getServer().getPluginManager().registerEvents(banListener, this);
        getServer().getPluginManager().registerEvents(new SafeUnbanRedirectListener(), this);

        panelSyncService = new PanelSyncService(this, moderationStore);
        panelSyncService.start();
        getServer().getOnlinePlayers().forEach(banListener::enforce);

        analyticsService = new AnalyticsService(this, moderationStore, panelSyncService);
        getServer().getPluginManager().registerEvents(analyticsService, this);
        analyticsService.start();

        Objects.requireNonNull(getCommand("adm"), "Comando adm ausente no plugin.yml")
                .setExecutor(new AdminCommand(this));
        getLogger().info("ZetraAdmin " + getDescription().getVersion() + " habilitado com bans em SQL e sincronizacao autoritativa.");
    }

    @Override
    public void onDisable() {
        if (analyticsService != null) {
            analyticsService.stop();
        }
        if (panelSyncService != null) {
            panelSyncService.stop();
        }
        if (moderationStore != null) {
            moderationStore.save();
            moderationStore.close();
        }
        if (limitManager != null) {
            limitManager.saveLimits();
        }
        getLogger().info("ZetraAdmin desabilitado.");
    }

    public static ZetraAdmin getInstance() {
        return instance;
    }

    public LimitManager getLimitManager() {
        return limitManager;
    }

    public LogManager getLogManager() {
        return logManager;
    }

    public ModerationStore getModerationStore() {
        return moderationStore;
    }

    public BanListener getBanListener() {
        return banListener;
    }

    public PanelSyncService getPanelSyncService() {
        return panelSyncService;
    }
}
