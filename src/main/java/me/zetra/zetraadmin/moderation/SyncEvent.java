package me.zetra.zetraadmin.moderation;

import java.util.LinkedHashMap;
import java.util.Map;

public record SyncEvent(String eventId, String type, Map<String, Object> data, long createdAt) {
    public SyncEvent {
        data = new LinkedHashMap<>(data == null ? Map.of() : data);
    }
}
