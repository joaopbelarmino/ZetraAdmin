package me.zetra.zetraadmin.analytics;

import org.bukkit.entity.Player;

public final class ZetraAdminAnalyticsAPI {
    private static volatile AnalyticsService service;

    private ZetraAdminAnalyticsAPI() {
    }

    static void bind(AnalyticsService analyticsService) {
        service = analyticsService;
    }

    public static void trackEvent(Player player, String eventType, String category, double value, String extra) {
        AnalyticsService current = service;
        if (current != null) current.trackEvent(player, eventType, category, value, extra);
    }
}
