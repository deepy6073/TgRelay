package world.losos.tgrelay;

import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * Self-mute: {@code /tgrelay off} keeps a player's chat out of the public chats.
 * The message is not lost — it goes to the moderation channel (a separate chat, or a DM
 * to every op that linked a Telegram account) with a {@code [mute]} prefix.
 */
public final class TgRelay extends JavaPlugin {

    /** Сообщение в очереди на отправку. targets == null — обычные чаты из конфига. */
    private static final class Outgoing {
        final String text;
        final List<String> targets;

        Outgoing(String text, List<String> targets) {
            this.text = text;
            this.targets = targets;
        }
    }

    private static final class LinkCode {
        final UUID player;
        final long expiresAt;

        LinkCode(UUID player, long expiresAt) {
            this.player = player;
            this.expiresAt = expiresAt;
        }
    }

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long LINK_CODE_TTL_MS = 10 * 60_000L;

    private static TgRelay instance;

    private final BlockingQueue<Outgoing> outbox = new LinkedBlockingQueue<>(500);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong offset = new AtomicLong(0);
    private final java.util.Deque<long[]> pendingTimes = new java.util.ArrayDeque<>();
    private final java.util.Deque<String> pendingLines = new java.util.ArrayDeque<>();
    private boolean holdWhenEmpty;
    private int holdMaxMinutes;
    private int holdMaxMessages;

    private TelegramApi api;
    private Thread poller;
    private Thread sender;
    private Path stateFile;
    private Messages messages;

    private final List<String> chats = new java.util.concurrent.CopyOnWriteArrayList<>();
    private List<String> listenChats;
    private String formatFromMc;
    private String formatToMc;
    private int pollTimeout;
    private int messagesPerSecond;

    /* ── заглушка своих сообщений ── */
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Map<String, LinkCode> linkCodes = new ConcurrentHashMap<>();
    private final Links links = new Links();
    private final SecureRandom random = new SecureRandom();
    private Path mutedFile;
    private boolean muteEnabled;
    private boolean muteEvents;
    private String mutePrefix;
    private String moderationChat;

    static void log(String message) {
        if (instance != null) {
            instance.getLogger().info(message);
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        messages = new Messages(this, getConfig().getString("language", "en"));
        if (!loadSettings()) {
            return;
        }

        api = new TelegramApi(getConfig().getString("bot.token", ""), messages);

        // offset переживает перезапуск: иначе после рестарта мост заново
        // прочитает всю накопившуюся историю и вывалит её в игровой чат
        stateFile = getDataFolder().toPath().resolve("state.json");
        offset.set(loadOffset());

        mutedFile = getDataFolder().toPath().resolve("muted.txt");
        loadMuted();

        links.open(getDataFolder().toPath().resolve("linked.txt"));
        importLegacyLinks();

        running.set(true);

        poller = new Thread(this::pollLoop, "TgRelay-poller");
        poller.setDaemon(true);
        poller.start();

        sender = new Thread(this::sendLoop, "TgRelay-sender");
        sender.setDaemon(true);
        sender.start();

        getServer().getPluginManager().registerEvents(new McEvents(this), this);
        getLogger().info(messages.get("started", chats.size(), pollTimeout));
        if (links.size() > 0) {
            getLogger().info(messages.get("links-loaded", links.size()));
        }
    }

    /** Придерживает сообщение до первого входа игрока (с TTL и лимитом). */
    private void hold(String line) {
        synchronized (pendingLines) {
            pendingLines.addLast(line);
            pendingTimes.addLast(new long[]{System.currentTimeMillis()});

            while (pendingLines.size() > holdMaxMessages) {
                pendingLines.removeFirst();
                pendingTimes.removeFirst();
            }
            long deadline = System.currentTimeMillis() - holdMaxMinutes * 60_000L;
            while (!pendingTimes.isEmpty() && pendingTimes.peekFirst()[0] < deadline) {
                pendingTimes.removeFirst();
                pendingLines.removeFirst();
            }
        }
    }

    /** Отдаёт придержанные сообщения в игру — вызывается при входе игрока. */
    void flushPending() {
        java.util.List<String> lines;
        synchronized (pendingLines) {
            lines = new java.util.ArrayList<>(pendingLines);
            pendingLines.clear();
            pendingTimes.clear();
        }
        if (lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            getServer().dispatchCommand(getServer().getConsoleSender(), "tellraw @a " + jsonText(line));
        }
        log(messages.get("flushed", lines.size()));
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
            log(messages.get("state-read-error", String.valueOf(error)));
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
            log(messages.get("state-write-error", String.valueOf(error)));
        }
    }

    /* ── заглушка: состояние ── */

    private void loadMuted() {
        muted.clear();
        if (mutedFile == null || !Files.isRegularFile(mutedFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(mutedFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                try {
                    muted.add(UUID.fromString(trimmed));
                } catch (IllegalArgumentException ignored) {
                    // битая строка — ну и ладно
                }
            }
        } catch (IOException error) {
            log("не смог прочитать muted.txt: " + error);
        }
    }

    private void saveMuted() {
        if (mutedFile == null) {
            return;
        }
        try {
            Path parent = mutedFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            muted.stream().map(UUID::toString).sorted().forEach(lines::add);
            Files.write(mutedFile, lines, StandardCharsets.UTF_8);
        } catch (IOException error) {
            log("не смог записать muted.txt: " + error);
        }
    }

    /**
     * Старая база TelegramChat: plugins/TelegramChat/data.json, поле linkedChats
     * (telegram id -> UUID игрока). Берём только админов: остальным связкам в новом
     * плагине делать нечего, а чужие персональные данные без нужды тащить не надо.
     */
    private void importLegacyLinks() {
        if (links.size() > 0) {
            return;
        }
        Path legacy = getDataFolder().toPath().getParent().resolve("TelegramChat").resolve("data.json");
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(legacy, StandardCharsets.UTF_8));
            Object rawLinked = root.get("linkedChats");
            if (!(rawLinked instanceof Map)) {
                return;
            }
            Set<UUID> ops = operatorIds();
            int added = 0;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawLinked).entrySet()) {
                String telegramId = String.valueOf(entry.getKey());
                try {
                    UUID player = UUID.fromString(String.valueOf(entry.getValue()));
                    if (!ops.contains(player)) {
                        continue;
                    }
                    links.put(telegramId, player);
                    added++;
                } catch (IllegalArgumentException ignored) {
                    // не UUID — пропускаем
                }
            }
            if (added > 0) {
                links.save();
                log(messages.get("links-imported", added));
            }
        } catch (Exception error) {
            log(messages.get("links-import-failed", String.valueOf(error)));
        }
    }

    /* ── кто здесь админ ── */

    /** UUID всех операторов сервера: op-лист, а не список в конфиге. */
    Set<UUID> operatorIds() {
        Set<UUID> result = new HashSet<>();
        try {
            for (OfflinePlayer operator : getServer().getOperators()) {
                if (operator.isOp()) {
                    result.add(operator.getUniqueId());
                }
            }
        } catch (Exception error) {
            log("не смог прочитать ops.json: " + error);
        }
        return result;
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
        getLogger().info(messages.get("stopped"));
    }

    private boolean loadSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();
        messages = new Messages(this, config.getString("language", "en"));

        String token = config.getString("bot.token", "");
        if (token == null || token.isEmpty() || token.startsWith("PUT_")) {
            getLogger().severe(messages.get("no-token"));
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        chats.clear();
        chats.addAll(config.getStringList("bot.chats"));
        listenChats = new java.util.ArrayList<>(config.getStringList("bot.listen"));
        if (listenChats.isEmpty()) {
            listenChats = chats;   // по умолчанию читаем оттуда же, куда пишем
        }
        if (chats.isEmpty()) {
            getLogger().severe(messages.get("no-chats"));
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        formatFromMc = config.getString("format.from-minecraft", "%player%: %message%");
        formatToMc = config.getString("format.to-minecraft", "[TG] %sender%: %message%");
        pollTimeout = Math.max(5, Math.min(50, config.getInt("polling.timeout-seconds", 25)));
        messagesPerSecond = Math.max(1, Math.min(20, config.getInt("queue.messages-per-second", 3)));
        holdWhenEmpty = config.getBoolean("delivery.hold-when-empty", true);
        holdMaxMinutes = Math.max(1, config.getInt("delivery.hold-max-minutes", 10));
        holdMaxMessages = Math.max(1, config.getInt("delivery.hold-max-messages", 20));

        muteEnabled = config.getBoolean("mute.enabled", true);
        muteEvents = !"chat".equalsIgnoreCase(config.getString("mute.scope", "all"));
        mutePrefix = config.getString("mute.prefix", "[mute] ");
        moderationChat = config.getString("moderation.chat", "");
        if (moderationChat == null) {
            moderationChat = "";
        }
        return true;
    }

    /* ── Minecraft -> Telegram ── */

    /** Чат игрока. Если он заглушён — сообщение уйдёт только админам. */
    void relayChat(Player player, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String line = formatFromMc
                .replace("%player%", player.getName())
                .replace("%message%", message);
        deliver(player.getUniqueId(), line, true);
    }

    /** Событие игрока: вход, выход, смерть, достижение. */
    void relayEvent(Player player, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        deliver(player.getUniqueId(), line, muteEvents);
    }

    private void deliver(UUID player, String line, boolean muteApplies) {
        if (muteApplies && muteEnabled && muted.contains(player)) {
            relayMuted(line);
            return;
        }
        enqueue(line);
    }

    /** Заглушённое сообщение: админский чат, личка привязанным опам или консоль. */
    private void relayMuted(String line) {
        String text = mutePrefix + line;
        List<String> targets = moderationTargets();
        if (targets.isEmpty()) {
            log(messages.get("muted-console", text));
            return;
        }
        enqueueTo(targets, text);
    }

    /** Куда уходят заглушённые сообщения. Пустой список — только консоль. */
    private List<String> moderationTargets() {
        if (moderationChat != null && !moderationChat.isBlank()) {
            return List.of(moderationChat);
        }
        return links.telegramIdsOf(operatorIds());
    }

    /** Puts an already formatted line into the outbound queue (обычные чаты). */
    void enqueue(String text) {
        enqueueTo(null, text);
    }

    private void enqueueTo(List<String> targets, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (text.length() > 4000) {
            text = text.substring(0, 4000);
        }
        if (!outbox.offer(new Outgoing(text, targets))) {
            log(messages.get("queue-full", text.substring(0, Math.min(60, text.length()))));
        }
    }

    private void sendLoop() {
        while (running.get()) {
            try {
                Outgoing item = outbox.poll(1, TimeUnit.SECONDS);
                if (item == null) {
                    continue;
                }
                List<String> targets = item.targets != null ? item.targets : chats;
                for (String chat : targets) {
                    if (!running.get()) {
                        return;
                    }
                    TelegramApi.Result result = api.send(chat, item.text);
                    if (result == TelegramApi.Result.GONE) {
                        // пользователь заблокировал бота или чат удалён — убираем насовсем,
                        // иначе каждый чих в игре бьётся в эти двери
                        if (item.targets == null && chats.remove(chat)) {
                            log(messages.get("chat-disabled", chat));
                        } else if (item.targets != null) {
                            log(messages.get("admin-unreachable", chat));
                        }
                        continue;
                    }
                    Thread.sleep(1000L / messagesPerSecond);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                log(messages.get("sender-error", String.valueOf(error)));
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

                    if (tryLink(update)) {
                        continue;
                    }
                    if (!listenChats.contains(update.chatId)) {
                        continue;   // ignore chats we were not configured for
                    }

                    String line = formatToMc
                            .replace("%sender%", update.sender)
                            .replace("%message%", update.text);
                    String command = "tellraw @a " + jsonText(line);
                    getServer().getScheduler().runTask(this, () -> {
                        if (getServer().getOnlinePlayers().isEmpty()) {
                            if (holdWhenEmpty) {
                                hold(line);
                                log(messages.get("held", line));
                            } else {
                                log(messages.get("dropped", line));
                            }
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
                log(messages.get("poll-failed", String.valueOf(error)));
                try {
                    Thread.sleep(5000);   // back off instead of hammering Telegram
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /* ── привязка telegram -> игрок ── */

    /** Сообщение боту с кодом из {@code /tgrelay link}: связываем аккаунт с игроком. */
    private boolean tryLink(TelegramApi.Update update) {
        if (update.fromId == null || update.fromId.isEmpty() || linkCodes.isEmpty()) {
            return false;
        }
        String text = update.text == null ? "" : update.text.trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("/start")) {
            text = text.substring(6).trim();
        }
        String code = Links.normalise(text);
        if (code.isEmpty() || code.length() > 12) {
            return false;
        }
        LinkCode pending = linkCodes.get(code);
        if (pending == null) {
            return false;
        }
        linkCodes.remove(code);
        if (pending.expiresAt < System.currentTimeMillis()) {
            return true;
        }

        UUID player = pending.player;
        links.put(update.fromId, player);
        String name = player.toString();
        Player online = getServer().getPlayer(player);
        if (online != null) {
            name = online.getName();
            getServer().getScheduler().runTask(this, () ->
                    online.sendMessage(Component.text(messages.get("link-done"))));
        }
        log(messages.get("link-log", name, update.fromId));
        return true;
    }

    private String newLinkCode(UUID player) {
        long now = System.currentTimeMillis();
        linkCodes.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder builder = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            String code = builder.toString();
            if (!linkCodes.containsKey(code)) {
                linkCodes.put(code, new LinkCode(player, now + LINK_CODE_TTL_MS));
                return code;
            }
        }
        return null;
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

    /* ── команды ── */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text(messages.get("usage")));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "off":
                return setMuted(sender, true);
            case "on":
                return setMuted(sender, false);
            case "link":
                return link(sender);
            case "unlink":
                return unlink(sender);
            case "status":
                return status(sender);
            case "reload":
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (loadSettings()) {
                    sender.sendMessage(Component.text(messages.get("reloaded")));
                } else {
                    sender.sendMessage(Component.text(messages.get("reload-failed")));
                }
                return true;
            case "testmute":
                if (!requireAdmin(sender)) {
                    return true;
                }
                return testModeration(sender);
            default:
                sender.sendMessage(Component.text(messages.get("usage")));
                return true;
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("tgrelay.admin")) {
            return true;
        }
        sender.sendMessage(Component.text(messages.get("no-permission")));
        return false;
    }

    private boolean setMuted(CommandSender sender, boolean mute) {
        if (!muteEnabled) {
            sender.sendMessage(Component.text(messages.get("mute-disabled")));
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text(messages.get("players-only")));
            return true;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        if (mute) {
            muted.add(uuid);
        } else {
            muted.remove(uuid);
        }
        saveMuted();
        player.sendMessage(Component.text(messages.get(mute ? "mute-on" : "mute-off")));
        log(messages.get(mute ? "mute-log-on" : "mute-log-off", player.getName()));
        return true;
    }

    private boolean link(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text(messages.get("players-only")));
            return true;
        }
        Player player = (Player) sender;
        String code = newLinkCode(player.getUniqueId());
        if (code == null) {
            sender.sendMessage(Component.text(messages.get("link-busy")));
            return true;
        }
        sender.sendMessage(Component.text(messages.get("link-code", code, 10)));
        return true;
    }

    private boolean unlink(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text(messages.get("players-only")));
            return true;
        }
        Player player = (Player) sender;
        boolean had = !links.telegramIdsOf(Set.of(player.getUniqueId())).isEmpty();
        links.removePlayer(player.getUniqueId());
        sender.sendMessage(Component.text(messages.get(had ? "unlink-done" : "unlink-none")));
        return true;
    }

    private boolean status(CommandSender sender) {
        boolean admin = sender.hasPermission("tgrelay.admin");
        if (!admin) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text(messages.get("no-permission")));
                return true;
            }
            Player player = (Player) sender;
            boolean mute = muted.contains(player.getUniqueId());
            sender.sendMessage(Component.text(messages.get("status-player",
                    messages.get(mute ? "state-muted" : "state-normal"))));
            return true;
        }

        sender.sendMessage(Component.text(messages.get("status",
                chats.size(), outbox.size(), offset.get(), pendingLines.size(),
                muted.size(), links.size())));
        sender.sendMessage(Component.text(messages.get("moderation-status", moderationWhere())));
        return true;
    }

    /** Человеческое описание того, куда уходят `[mute]`-сообщения. */
    private String moderationWhere() {
        if (moderationChat != null && !moderationChat.isBlank()) {
            return messages.get("moderation-chat", moderationChat);
        }
        Set<UUID> ops = operatorIds();
        int linked = links.linkedPlayers(ops);
        if (linked > 0) {
            return messages.get("moderation-dm", linked, ops.size());
        }
        return messages.get("moderation-console");
    }

    private boolean testModeration(CommandSender sender) {
        List<String> targets = moderationTargets();
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text(messages.get("moderation-console")));
            return true;
        }
        Set<UUID> ops = operatorIds();
        int recipients = moderationChat != null && !moderationChat.isBlank()
                ? 1
                : links.linkedPlayers(ops);
        relayMuted(messages.get("moderation-test-line"));
        sender.sendMessage(Component.text(messages.get("moderation-test-sent", recipients)));
        return true;
    }
}
