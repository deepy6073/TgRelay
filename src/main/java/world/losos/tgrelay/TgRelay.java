package world.losos.tgrelay;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relay between Minecraft chat and a Telegram bot.
 *
 * Threading model:
 *   - poller: one long-polling request at a time (getUpdates?timeout=25), so a whole
 *     cycle costs one connection instead of hundreds;
 *   - sender: a queue drained at a fixed rate, so a busy server cannot flood Telegram
 *     and get the bot rate-limited;
 *   - both stop cleanly on disable, no leftover threads or sockets.
 */
public final class TgRelay extends JavaPlugin {

    private static TgRelay instance;

    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>(500);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong offset = new AtomicLong(0);
    private final AtomicLong skippedNoPlayers = new AtomicLong(0);

    private TelegramApi api;
    private Thread poller;
    private Thread sender;
    private Path stateFile;

    private List<String> chats;
    private String formatFromMc;
    private String formatToMc;
    private int pollTimeout;
    private int messagesPerSecond;

    static void log(String message) {
        if (instance != null) {
            instance.getLogger().info(message);
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        if (!loadSettings()) {
            return;
        }

        api = new TelegramApi(getConfig().getString("bot.token", ""));

        // offset переживает перезапуск: иначе после рестарта мост заново
        // прочитает всю накопившуюся историю и вывалит её в игровой чат
        stateFile = getDataFolder().toPath().resolve("state.json");
        offset.set(loadOffset());

        running.set(true);

        poller = new Thread(this::pollLoop, "TgRelay-poller");
        poller.setDaemon(true);
        poller.start();

        sender = new Thread(this::sendLoop, "TgRelay-sender");
        sender.setDaemon(true);
        sender.start();

        getServer().getPluginManager().registerEvents(new McEvents(this), this);
        getLogger().info("Relay started, " + chats.size() + " chat(s), long polling " + pollTimeout + "s");
    }

    private long loadOffset() {
        try {
            if (stateFile != null && Files.exists(stateFile)) {
                String raw = Files.readString(stateFile, StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher =
                        java.util.regex.Pattern.compile("\"offset\"\\s*:\\s*(\\d+)").matcher(raw);
                if (matcher.find()) {
                    return Long.parseLong(matcher.group(1));
                }
            }
        } catch (Exception error) {
            log("\u043d\u0435 \u043f\u0440\u043e\u0447\u0438\u0442\u0430\u043b state.json: " + error);
        }
        return 0;
    }

    private void saveOffset(long value) {
        try {
            if (stateFile == null) {
                return;
            }
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(stateFile, "{\"offset\":" + value + "}", StandardCharsets.UTF_8);
        } catch (IOException error) {
            log("не записал state.json: " + error);
        }
    }

    @Override
    public void onDisable() {
        running.set(false);
        if (poller != null) {
            poller.interrupt();
        }
        if (sender != null) {
            sender.interrupt();
        }
        getLogger().info("Relay stopped");
    }

    private boolean loadSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();

        String token = config.getString("bot.token", "");
        if (token == null || token.isEmpty() || token.startsWith("PUT_")) {
            getLogger().severe("bot.token is empty — put your bot token into config.yml, relay disabled");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        chats = config.getStringList("bot.chats");
        if (chats.isEmpty()) {
            getLogger().severe("bot.chats is empty — nobody to send to, relay disabled");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        formatFromMc = config.getString("format.from-minecraft", "%player%: %message%");
        formatToMc = config.getString("format.to-minecraft", "[TG] %sender%: %message%");
        pollTimeout = Math.max(5, Math.min(50, config.getInt("polling.timeout-seconds", 25)));
        messagesPerSecond = Math.max(1, Math.min(20, config.getInt("queue.messages-per-second", 3)));
        return true;
    }

    /** Minecraft -> Telegram. Called from event handlers, never blocks. */
    void relayToTelegram(String player, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        enqueue(formatFromMc
                .replace("%player%", player)
                .replace("%message%", message));
    }

    /** Puts an already formatted line into the outbound queue. */
    void enqueue(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (text.length() > 4000) {
            text = text.substring(0, 4000);
        }
        if (!outbox.offer(text)) {
            log("queue is full, dropping: " + text.substring(0, Math.min(60, text.length())));
        }
    }

    private void sendLoop() {
        while (running.get()) {
            try {
                String text = outbox.poll(1, TimeUnit.SECONDS);
                if (text == null) {
                    continue;
                }
                for (String chat : chats) {
                    if (!running.get()) {
                        return;
                    }
                    api.send(chat, text);
                    Thread.sleep(1000L / messagesPerSecond);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                log("sender error: " + error);
            }
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                List<TelegramApi.Update> updates = api.poll(offset.get() + 1, pollTimeout);
                long highest = offset.get();
                for (TelegramApi.Update update : updates) {
                    highest = Math.max(highest, update.updateId);

                    if (!chats.contains(update.chatId)) {
                        continue;   // ignore chats we were not configured for
                    }

                    String line = formatToMc
                            .replace("%sender%", update.sender)
                            .replace("%message%", update.text);
                    String command = "tellraw @a " + jsonText(line);
                    getServer().getScheduler().runTask(this, () -> {
                        if (getServer().getOnlinePlayers().isEmpty()) {
                            // tellraw @a без игроков отвечает «No player was found» — вместо этого
                            // говорим по-человечески, что сообщение пропущено
                            skippedNoPlayers.incrementAndGet();
                            log("некому сообщать: в игре никого, сообщение из Telegram пропущено -> " + line);
                            return;
                        }
                        getServer().dispatchCommand(getServer().getConsoleSender(), command);
                    });
                }
                if (highest != offset.get()) {
                    offset.set(highest);
                    saveOffset(highest);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                log("poll failed: " + error);
                try {
                    Thread.sleep(5000);   // back off instead of hammering Telegram
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static String jsonText(String text) {
        StringBuilder builder = new StringBuilder("{\"text\":\"");
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': break;
                default:
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
            }
        }
        return builder.append("\",\"color\":\"gray\"}").toString();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("tgrelay.admin")) {
            sender.sendMessage(Component.text("No permission"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (loadSettings()) {
                sender.sendMessage(Component.text("TgRelay: config reloaded"));
            } else {
                sender.sendMessage(Component.text("TgRelay: config is broken, see console"));
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(Component.text("TgRelay: chats=" + chats.size()
                    + ", queued=" + outbox.size()
                    + ", offset=" + offset.get()
                    + ", skipped(no players)=" + skippedNoPlayers.get()));
            return true;
        }
        sender.sendMessage(Component.text("Usage: /tgrelay reload|status"));
        return true;
    }
}
