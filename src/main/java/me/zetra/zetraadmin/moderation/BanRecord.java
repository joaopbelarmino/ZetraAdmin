package me.zetra.zetraadmin.moderation;

import java.util.UUID;

public record BanRecord(
    String eventId,
    UUID playerUuid,
    String nick,
    String reason,
    String actor,
    String origin,
    long createdAt,
    long expiresAt,
    boolean active
) {
    public boolean isExpired(long now) {
        return active && expiresAt > 0 && expiresAt <= now;
    }

    public boolean isPermanent() {
        return expiresAt <= 0;
    }
}
