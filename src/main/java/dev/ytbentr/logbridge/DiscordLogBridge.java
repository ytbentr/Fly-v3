package dev.ytbentr.logbridge;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class DiscordLogBridge extends JavaPlugin {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<String> pending = new ArrayList<>();
    private Path logPath;
    private long position;
    private BukkitTask task;
    private String webhook;
    private int maxLines;

    @Override public void onEnable() {
        saveDefaultConfig();
        webhook = getConfig().getString("webhook-url", "").trim();
        maxLines = Math.max(1, Math.min(12, getConfig().getInt("max-lines-per-message", 8)));
        if (!webhook.startsWith("https://discord.com/api/webhooks/") && !webhook.startsWith("https://discordapp.com/api/webhooks/")) {
            getLogger().severe("Webhook URL is missing or invalid. Set it in plugins/DiscordLogBridge/config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logPath = getServer().getWorldContainer().toPath().resolve("logs").resolve("latest.log");
        try {
            if (!Files.exists(logPath)) throw new IllegalStateException("logs/latest.log was not found: " + logPath);
            position = Files.size(logPath);
        } catch (Exception ex) {
            getLogger().severe("Cannot read latest.log: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        long interval = Math.max(20, getConfig().getLong("poll-interval-ticks", 20));
        task = getServer().getScheduler().runTaskTimer(this, this::readNewLines, interval, interval);
        if (getConfig().getBoolean("send-startup-message", true)) send("Minecraft server started. Log forwarding is active.");
        getLogger().info("Discord log forwarding enabled: " + logPath);
    }

    private void readNewLines() {
        try {
            if (!Files.exists(logPath)) return;
            long size = Files.size(logPath);
            if (size < position) position = 0L;
            if (size == position) return;
            try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "r")) {
                file.seek(position);
                String line;
                while ((line = file.readLine()) != null) {
                    pending.add(new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
                    if (pending.size() >= maxLines) flushPending();
                }
                position = file.getFilePointer();
            }
            if (!pending.isEmpty()) flushPending();
        } catch (Exception ex) {
            getLogger().warning("Could not read latest.log: " + ex.getMessage());
        }
    }

    private synchronized void flushPending() {
        if (pending.isEmpty()) return;
        String message = String.join("\n", pending);
        pending.clear();
        while (message.length() > 1850) {
            int cut = message.lastIndexOf('\n', 1850);
            if (cut < 1) cut = 1850;
            send("```\n" + message.substring(0, cut) + "\n```");
            message = message.substring(cut).stripLeading();
        }
        if (!message.isBlank()) send("```\n" + message + "\n```");
    }

    private void send(String content) {
        if (content.length() > 1990) content = content.substring(0, 1990);
        String body = "{\"content\":\"" + jsonEscape(content) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    getLogger().warning("Discord webhook returned HTTP " + response.statusCode());
            } catch (Exception ex) {
                getLogger().warning("Discord send failed: " + ex.getMessage());
            }
        });
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    @Override public void onDisable() {
        if (task != null) task.cancel();
        if (logPath != null && webhook != null && webhook.startsWith("https://discord")) {
            readNewLines();
            flushPending();
            send("Minecraft server is stopping or the log plugin is being disabled.");
        }
    }
}
