/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.configuration.file.FileConfiguration
 *  org.bukkit.configuration.file.YamlConfiguration
 */
package me.zetra.zetraadmin.managers;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import me.zetra.zetraadmin.ZetraAdmin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class LimitManager {
    private final ZetraAdmin plugin;
    private File file;
    private FileConfiguration config;
    private final Map<String, StaffData> staffLimits = new HashMap<String, StaffData>();

    public LimitManager(ZetraAdmin plugin) {
        this.plugin = plugin;
    }

    public void loadLimits() {
        this.file = new File(this.plugin.getDataFolder(), "staff-limits.yml");
        if (!this.file.exists()) {
            this.plugin.saveResource("staff-limits.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration((File)this.file);
        if (this.config.contains("players")) {
            for (String nick : this.config.getConfigurationSection("players").getKeys(false)) {
                int limit = this.config.getInt("players." + nick + ".limit");
                int usedToday = this.config.getInt("players." + nick + ".usedToday");
                String lastReset = this.config.getString("players." + nick + ".lastReset");
                StaffData data = new StaffData(limit, usedToday, lastReset);
                this.checkAndReset(nick, data);
                this.staffLimits.put(nick.toLowerCase(), data);
            }
        }
    }

    public void saveLimits() {
        for (Map.Entry<String, StaffData> entry : this.staffLimits.entrySet()) {
            String nick = entry.getKey();
            StaffData data = entry.getValue();
            this.config.set("players." + nick + ".limit", (Object)data.limit);
            this.config.set("players." + nick + ".usedToday", (Object)data.usedToday);
            this.config.set("players." + nick + ".lastReset", (Object)data.lastReset);
        }
        try {
            this.config.save(this.file);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void checkAndReset(String nick, StaffData data) {
        String today = LocalDate.now().toString();
        if (!today.equals(data.lastReset)) {
            data.usedToday = 0;
            data.lastReset = today;
        }
    }

    public boolean canPerformAction(String nick) {
        StaffData data = this.staffLimits.get(nick.toLowerCase());
        if (data == null) {
            return true;
        }
        this.checkAndReset(nick, data);
        return data.usedToday < data.limit;
    }

    public void incrementUsed(String nick) {
        StaffData data = this.staffLimits.get(nick.toLowerCase());
        if (data != null) {
            ++data.usedToday;
            this.saveLimits();
        }
    }

    public void setLimit(String nick, int limit) {
        StaffData data = this.staffLimits.getOrDefault(nick.toLowerCase(), new StaffData(limit, 0, LocalDate.now().toString()));
        data.limit = limit;
        this.staffLimits.put(nick.toLowerCase(), data);
        this.saveLimits();
    }

    public int getUsed(String nick) {
        StaffData data = this.staffLimits.get(nick.toLowerCase());
        return data != null ? data.usedToday : 0;
    }

    public int getLimit(String nick) {
        StaffData data = this.staffLimits.get(nick.toLowerCase());
        return data != null ? data.limit : -1;
    }

    private static class StaffData {
        int limit;
        int usedToday;
        String lastReset;

        StaffData(int limit, int usedToday, String lastReset) {
            this.limit = limit;
            this.usedToday = usedToday;
            this.lastReset = lastReset;
        }
    }
}

