/*
 * Decompiled with CFR 0.152.
 */
package me.zetra.zetraadmin.managers;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import me.zetra.zetraadmin.ZetraAdmin;

public class LogManager {
    private final ZetraAdmin plugin;
    private final File logFolder;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    public LogManager(ZetraAdmin plugin) {
        this.plugin = plugin;
        this.logFolder = new File(plugin.getDataFolder(), "logs");
        if (!this.logFolder.exists()) {
            this.logFolder.mkdirs();
        }
    }

    public void log(String senderName, String command) {
        String date = LocalDate.now().toString();
        String time = LocalDateTime.now().format(this.timeFormatter);
        File logFile = new File(this.logFolder, date + ".log");
        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw);){
            pw.println("[" + time + "] " + senderName + ": " + command);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe("Nao foi possivel salvar o log: " + e.getMessage());
        }
    }
}

