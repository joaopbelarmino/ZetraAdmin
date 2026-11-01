package me.zetra.zetraadmin.moderation;

import me.zetra.zetraadmin.ZetraAdmin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModerationRegressionTest {
    @TempDir Path directory;
    ZetraAdmin plugin;
    ModerationStore store;
    UUID bedrock = UUID.fromString("00000000-0000-0000-0009-01f8f007f45b");

    @BeforeEach void setup() {
        plugin = mock(ZetraAdmin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("moderation-test"));
        store = new ModerationStore(plugin);
        store.load();
    }

    @AfterEach void cleanup() {
        if (store != null) {
            store.close();
        }
    }

    void ban() {
        store.applyBan("ban-event", bedrock, ".jaozinmDK", "teste", "Staff", "panel",
            System.currentTimeMillis(), 0L, "panel-command", true);
    }

    @Test void bedrockBanSurvivesReloadAndNameCase() {
        ban();
        ModerationStore reloaded = new ModerationStore(plugin);
        try {
            reloaded.load();
            assertTrue(reloaded.activeBan(bedrock, ".JAOZINMDK").isPresent());
            assertTrue(reloaded.activeBan(UUID.randomUUID(), ".jaozinmdk").isPresent());
            assertTrue(reloaded.activeBan(UUID.randomUUID(), "jaozinmdk").isEmpty());
            assertTrue(reloaded.commandApplied("panel-command"));
            assertEquals("ban_applied", reloaded.pendingEvents(5).getFirst().type());
        } finally {
            reloaded.close();
        }
    }

    @Test void authoritativeSnapshotRestoresMissingBansAndRemovesStaleOnes() {
        UUID staleUuid = UUID.randomUUID();
        store.applyBan("stale-event", staleUuid, "stalePlayer", "antigo", "Staff", "panel",
            1_000L, 0L, "stale-command", false);

        BanRecord authoritative = new BanRecord(
            "panel-ban-1",
            bedrock,
            ".jaozinmDK",
            "ban ativo no painel",
            "Staff",
            "panel",
            2_000L,
            0L,
            true
        );

        ModerationStore.ReconcileResult result = store.reconcileAuthoritativeBans(
            java.util.List.of(authoritative),
            3_000L
        );

        assertEquals(1, result.before());
        assertEquals(1, result.after());
        assertEquals(1, result.authoritativeTotal());
        assertTrue(store.activeBan(bedrock, ".jaozinmdk").isPresent());
        assertTrue(store.activeBan(staleUuid, "stalePlayer").isEmpty());
    }

    @Test void optionalProductionCacheMigrationSmokeTest() throws Exception {
        String source = System.getProperty("zetra.productionCache", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!source.isBlank());
        Path migrationDirectory = Files.createDirectory(directory.resolve("production-cache"));
        Files.copy(Path.of(source), migrationDirectory.resolve("moderation-cache.yml"));

        ZetraAdmin migrationPlugin = mock(ZetraAdmin.class);
        when(migrationPlugin.getDataFolder()).thenReturn(migrationDirectory.toFile());
        when(migrationPlugin.getConfig()).thenReturn(new YamlConfiguration());
        when(migrationPlugin.getLogger()).thenReturn(Logger.getLogger("production-migration-test"));
        ModerationStore migrated = new ModerationStore(migrationPlugin);
        try {
            migrated.load();
            int expected = Integer.getInteger("zetra.expectedActiveBans", -1);
            assertTrue(migrated.activeBans().size() > 300);
            if (expected >= 0) assertEquals(expected, migrated.activeBans().size());
            assertTrue(Files.isRegularFile(migrationDirectory.resolve("moderation.db")));
            assertTrue(Files.isRegularFile(migrationDirectory.resolve("moderation-cache.migrated-backup.yml")));
        } finally {
            migrated.close();
        }
    }

    @Test void bedrockAndUnderscoreNamesDoNotShareIdentity() {
        UUID java = UUID.randomUUID();
        store.rememberPlayer(bedrock, ".jaozinmDK");
        store.rememberPlayer(java, "_jaozinmDK");
        assertEquals(bedrock, store.findKnownUuid(".jaozinmdk").orElseThrow());
        assertEquals(java, store.findKnownUuid("_jaozinmdk").orElseThrow());
    }

    @Test void banIsCheckedAtBothLoginStages() {
        ban();
        BanListener listener = new BanListener(plugin, store);
        AsyncPlayerPreLoginEvent pre = new AsyncPlayerPreLoginEvent(".jaozinmdk", InetAddress.getLoopbackAddress(), bedrock);
        listener.onPreLogin(pre);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, pre.getLoginResult());
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(bedrock);
        when(player.getName()).thenReturn(".jaozinmDK");
        PlayerLoginEvent login = new PlayerLoginEvent(player, "localhost", InetAddress.getLoopbackAddress());
        listener.onLogin(login);
        assertEquals(PlayerLoginEvent.Result.KICK_BANNED, login.getResult());
    }

    @Test void cancelledKickDoesNotReportSuccess() {
        ban();
        BanListener listener = new BanListener(plugin, store);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(bedrock);
        when(player.getName()).thenReturn(".jaozinmDK");
        doAnswer(call -> {
            PlayerKickEvent event = new PlayerKickEvent(player, call.getArgument(0), "");
            event.setCancelled(true);
            listener.observeBanKick(event);
            return null;
        }).when(player).kickPlayer(anyString());
        assertFalse(listener.enforce(player));
    }

    @Test void failedCommandStaysFailedAfterRestartAndRetry() {
        store.queueCommandFailure("failure-1", "cmd-1", "kick_cancelled");
        store.acknowledgeEvents(java.util.List.of("failure-1"));
        store.load();
        store.queueCommandDuplicate("retry-1", "cmd-1");
        SyncEvent event = store.pendingEvents(5).getFirst();
        assertEquals("command_failed", event.type());
        assertEquals("kick_cancelled", event.data().get("detail"));
    }

    @Test void unbanKeepsHistoryEventAndAllowsLogin() {
        ban();
        store.revokeBan("unban-event", bedrock, ".jaozinmdk", "teste", "Staff", "panel", "unban-command", System.currentTimeMillis(), true);
        store.load();
        assertTrue(store.activeBan(bedrock, ".jaozinmDK").isEmpty());
        assertEquals(2, store.pendingEvents(5).size());
    }

    @ParameterizedTest
    @CsvSource({
        "ban .jaozinmDK teste,adm ban .jaozinmDK teste",
        "essentials:ban .jaozinmDK teste,adm ban .jaozinmDK teste",
        "tempban .jaozinmDK 7d teste,adm tempban .jaozinmDK 7d teste",
        "unban .jaozinmDK,adm unban .jaozinmDK",
        "minecraft:pardon .jaozinmDK,adm unban .jaozinmDK",
        "ban,adm ban"
    })
    void vanillaAliasesUseAdm(String input, String expected) {
        assertEquals(expected, SafeUnbanRedirectListener.rewrite(input));
    }

    @Test void unrelatedCommandsAreNotRedirected() {
        assertNull(SafeUnbanRedirectListener.rewrite("adm ban player motivo"));
        assertNull(SafeUnbanRedirectListener.rewrite("other:ban player"));
        assertNull(SafeUnbanRedirectListener.rewrite("banip 127.0.0.1"));
    }
}
