package world.losos.tgrelay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
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
public final class TgRelay extends JavaPlugin implements TabCompleter {

    /** Сообщение в очереди на отправку. targets == null — обычные чаты из конфига. */
    private static final class Outgoing {
        final String text;
        final List<String> targets;
        /** Заглушённое сообщение: главный сервер отправит его в канал модерации, а не в общий чат. */
        final boolean muted;

        Outgoing(String text, List<String> targets, boolean muted) {
            this.text = text;
            this.targets = targets;
            this.muted = muted;
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

    /** Один сервер и свой бот — как было до 1.3.0. */
    static final String MODE_STANDALONE = "standalone";
    /** Бот живёт здесь: свой чат плюс чаты вторых серверов. */
    static final String MODE_HUB = "hub";
    /** Бота нет: чат уходит на главный сервер, его сообщения печатаются здесь. */
    static final String MODE_SPOKE = "spoke";

    /** В игре никого: не копить — сообщение уже разошлось по чатам бота (по умолчанию). */
    static final String WHEN_SKIP = "skip";
    /** В игре никого: придержать и показать, когда кто-то зайдёт. */
    static final String WHEN_WAIT = "wait";

    private static TgRelay instance;

    private final BlockingQueue<Outgoing> outbox = new LinkedBlockingQueue<>(500);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong offset = new AtomicLong(0);
    private final java.util.Deque<long[]> pendingTimes = new java.util.ArrayDeque<>();
    private final java.util.Deque<String> pendingLines = new java.util.ArrayDeque<>();
    /** Что делать с сообщением из Telegram, когда в игре никого: echo, hold или drop. */
    private String whenEmpty = WHEN_SKIP;
    private int holdMaxMinutes;
    private int holdMaxMessages;
    /** Чаты, где бот заблокирован или чат удалён: помним между рестартами. */
    private final Set<String> disabledChats = ConcurrentHashMap.newKeySet();

    private TelegramApi api;
    private Bridge bridge;
    private Thread poller;
    private Thread sender;
    private Path stateFile;
    private Messages messages;

    private final List<String> chats = new java.util.concurrent.CopyOnWriteArrayList<>();
    private List<String> listenChats;
    /** Сколько чатов прописано в конфиге — чтобы status показывал «13 из 24». */
    private int configuredChats;
    /** Чаты, подписавшиеся сами: привязка аккаунта кодом из игры. */
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private boolean linkingRequired = true;
    /** Кому и когда уже подсказали привязать аккаунт: не чаще раза в полчаса. */
    private final Map<String, Long> linkHints = new ConcurrentHashMap<>();
    /** id чата -> человеческое имя: только для логов и /tgrelay chats. */
    private final Map<String, String> chatNames = new ConcurrentHashMap<>();
    private String formatFromMc;
    private String formatToMc;
    private int pollTimeout;
    private int messagesPerSecond;

    /* ── несколько серверов на одном боте ── */
    private String serverName = "";
    private String serverEmoji = "";
    private String relayMode = MODE_STANDALONE;
    private String relayToken = "";
    private String relayBind = "";
    private String relayHub = "";
    private List<String> relaySpokes = new ArrayList<>();
    /** Настройки, с которыми поднят мост: после reload их смена требует перезапуска. */
    private String bridgeSignature = "";
    private String botToken = "";

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

    /* ── технические уведомления админам ── */
    private boolean noticesEnabled;
    private String noticesPrefix;
    private int pollFailureLimit;
    private final AtomicLong pollFailures = new AtomicLong(0);

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

        api = new TelegramApi(botToken, messages);

        // offset переживает перезапуск: иначе после рестарта мост заново
        // прочитает всю накопившуюся историю и вывалит её в игровой чат
        stateFile = getDataFolder().toPath().resolve("state.json");
        offset.set(loadOffset());

        mutedFile = getDataFolder().toPath().resolve("muted.txt");
        loadMuted();

        links.open(getDataFolder().toPath().resolve("linked.txt"));
        importLegacyLinks();

        startBridge();

        running.set(true);

        if (!isSpoke()) {
            poller = new Thread(this::pollLoop, "TgRelay-poller");
            poller.setDaemon(true);
            poller.start();
        }

        sender = new Thread(this::sendLoop, "TgRelay-sender");
        sender.setDaemon(true);
        sender.start();

        if (getCommand("tgrelay") != null) {
            getCommand("tgrelay").setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(new McEvents(this), this);
        if (isSpoke()) {
            getLogger().info(messages.get("started-spoke", serverName, relayHub));
        } else if (isHub()) {
            getLogger().info(messages.get("started-hub", chats.size(), pollTimeout, relaySpokes.size()));
        } else {
            getLogger().info(messages.get("started", chats.size(), pollTimeout));
        }
        if (links.size() > 0) {
            getLogger().info(messages.get("links-loaded", links.size()));
        }
        notifyAdmins(messages.get("server-started", serverName.isEmpty() ? "?" : serverName));
        refreshChatNames(true);
    }

    /**
     * Сообщение «сервер останавливается» отправляем напрямую: очередь к этому моменту
     * уже останавливается, и через неё сообщение не уйдёт.
     */
    private void sendStoppingNotice() {
        if (!noticesEnabled || api == null) {
            return;
        }
        List<String> targets = moderationTargets();
        if (targets.isEmpty()) {
            return;
        }
        String text = noticesPrefix + messages.get("server-stopping", serverName.isEmpty() ? "?" : serverName);
        for (String chat : targets) {
            try {
                api.send(chat, text);
            } catch (Exception ignored) {
                // сервер выключается: молча пропускаем
            }
        }
    }

    /** Поднимает HTTP-мост: главный сервер слушает вторые, второй слушает главного. */
    private void startBridge() {
        if (MODE_STANDALONE.equals(relayMode)) {
            return;
        }
        bridge = new Bridge(messages, isSpoke(), relayToken, relaySpokes, relayHub,
                new Bridge.Events() {
                    @Override
                    public void onSpokeEvent(String text, boolean muted) {
                        acceptFromSpoke(text, muted);
                    }

                    @Override
                    public void onInbound(String text) {
                        // копия от главного сервера: показываем, только если есть кому
                        showInGame(text, null);
                    }

                    @Override
                    public void onLinkCode(String code, String playerId, String playerName) {
                        try {
                            UUID player = UUID.fromString(playerId);
                            linkCodes.put(Links.normalise(code),
                                    new LinkCode(player, System.currentTimeMillis() + LINK_CODE_TTL_MS));
                            log(messages.get("link-code-from-spoke", playerName, code));
                        } catch (IllegalArgumentException broken) {
                            log("код привязки с плохим UUID: " + playerId);
                        }
                    }
                });
        bridge.self(new Bridge.Self() {
            @Override
            public String name() {
                return serverName;
            }

            @Override
            public int players() {
                return getServer().getOnlinePlayers().size();
            }
        });
        bridgeSignature = bridgeSignature();
        try {
            String bound = bridge.listen(relayBind);
            if (bound != null) {
                log(messages.get("bridge-listen", bound));
            }
        } catch (Exception error) {
            log(messages.get("bridge-failed", relayBind, String.valueOf(error)));
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
            Component text = Component.text(line, NamedTextColor.GRAY);
            getServer().getOnlinePlayers().forEach(player -> player.sendMessage(text));
        }
        log(messages.get("flushed", lines.size()));
    }

    private Path subscribedFile() {
        return getDataFolder().toPath().resolve("subscribed.txt");
    }

    private void loadSubscribed() {
        subscribed.clear();
        Path file = subscribedFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    subscribed.add(trimmed);
                }
            }
        } catch (IOException error) {
            log("не смог прочитать subscribed.txt: " + error);
        }
    }

    private void saveSubscribed() {
        Path file = subscribedFile();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            lines.add("# Чаты, которые привязали аккаунт кодом из игры: они получают рассылку.");
            subscribed.stream().sorted().forEach(lines::add);
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException error) {
            log("не смог записать subscribed.txt: " + error);
        }
    }

    /** Подписать чат на рассылку: привязка аккаунта и есть подписка. */
    private void addSubscriber(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        if (!subscribed.contains(chatId)) {
            subscribed.add(chatId);
            saveSubscribed();
        }
        if (!disabledChats.contains(chatId) && !chats.contains(chatId)) {
            chats.add(chatId);
            log(messages.get("chat-subscribed", chatLabel(chatId)));
            refreshChatNames(true);
        }
    }

    /** Незнакомцу подсказываем, как привязать аккаунт: не чаще раза в полчаса. */
    private void askToLink(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = linkHints.get(chatId);
        if (last != null && now - last < 30 * 60_000L) {
            return;
        }
        linkHints.put(chatId, now);
        enqueueTo(List.of(chatId), messages.get("link-required"));
        log(messages.get("link-required-log", chatId));
    }

    /** Подпись чата для логов: «имя» (id), а если имени нет — просто id. */
    String chatLabel(String chatId) {
        String name = chatNames.get(chatId);
        if (name == null || name.isBlank()) {
            return chatId;
        }
        return "\u00AB" + name + "\u00BB (" + chatId + ")";
    }

    private Path chatNamesFile() {
        return getDataFolder().toPath().resolve("chat-names.txt");
    }

    private void loadChatNames() {
        chatNames.clear();
        Path file = chatNamesFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int space = trimmed.indexOf(' ');
                if (space <= 0) {
                    continue;
                }
                chatNames.put(trimmed.substring(0, space), trimmed.substring(space + 1).trim());
            }
        } catch (IOException error) {
            log("не смог прочитать chat-names.txt: " + error);
        }
    }

    private void saveChatNames() {
        Path file = chatNamesFile();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            lines.add("# id чата и его имя в Telegram — только для логов, рассылке не мешает.");
            chatNames.forEach((id, name) -> lines.add(id + " " + name));
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException error) {
            log("не смог записать chat-names.txt: " + error);
        }
    }

    /** В фоне спрашиваем у Telegram имена чатов: старт не должен ждать сети. */
    private void refreshChatNames(boolean onlyMissing) {
        if (api == null || isSpoke()) {
            return;
        }
        Thread thread = new Thread(() -> {
            boolean changed = false;
            for (String chat : chats) {
                if (onlyMissing && !chatNames.getOrDefault(chat, "").isBlank()) {
                    continue;
                }
                String name = api.chatName(chat);
                if (!name.isBlank() && !name.equals(chatNames.get(chat))) {
                    chatNames.put(chat, name);
                    changed = true;
                }
                try {
                    Thread.sleep(120);   // не частить: у Telegram лимит на запросы
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (changed) {
                saveChatNames();
            }
        }, "TgRelay-names");
        thread.setDaemon(true);
        thread.start();
    }

    /** Файл с чатами, куда бот больше не пишет: их выкинул сам Telegram. */
    private Path disabledChatsFile() {
        return getDataFolder().toPath().resolve("disabled-chats.txt");
    }

    private void loadDisabledChats() {
        disabledChats.clear();
        Path file = disabledChatsFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                disabledChats.add(trimmed);
            }
        } catch (IOException error) {
            log("не смог прочитать disabled-chats.txt: " + error);
        }
    }

    private void saveDisabledChats() {
        Path file = disabledChatsFile();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            lines.add("# Чаты, в которых бот заблокирован или которые удалены.");
            lines.add("# Плагин перестал в них писать. Вернуть: удалить строку и /tgrelay reload.");
            disabledChats.stream().sorted().forEach(lines::add);
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException error) {
            log("не смог записать disabled-chats.txt: " + error);
        }
    }

    /** Сколько чатов сейчас отключено — для /tgrelay status. */
    int disabledChatsCount() {
        return disabledChats.size();
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
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
        sendStoppingNotice();
        getLogger().info(messages.get("stopped"));
    }

    private boolean loadSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();
        messages = new Messages(this, config.getString("language", "en"));

        serverName = text(config.getString("server.name", ""));
        serverEmoji = text(config.getString("server.emoji", ""));
        relayMode = text(config.getString("relay.mode", MODE_STANDALONE)).toLowerCase(Locale.ROOT);
        if (!MODE_HUB.equals(relayMode) && !MODE_SPOKE.equals(relayMode)) {
            relayMode = MODE_STANDALONE;
        }
        relayToken = text(config.getString("relay.token", ""));
        relayBind = text(config.getString("relay.bind", ""));
        relayHub = text(config.getString("relay.hub", ""));
        relaySpokes = new ArrayList<>();
        for (String url : config.getStringList("relay.spokes")) {
            String trimmed = text(url);
            if (!trimmed.isEmpty()) {
                relaySpokes.add(trimmed);
            }
        }

        botToken = text(config.getString("bot.token", ""));
        if (MODE_SPOKE.equals(relayMode)) {
            if (relayHub.isEmpty()) {
                getLogger().severe(messages.get("no-hub-url"));
                getServer().getPluginManager().disablePlugin(this);
                return false;
            }
        } else {
            if (botToken.isEmpty() || botToken.startsWith("PUT_")) {
                getLogger().severe(messages.get("no-token"));
                getServer().getPluginManager().disablePlugin(this);
                return false;
            }
        }
        if (!MODE_STANDALONE.equals(relayMode) && relayToken.isEmpty()) {
            getLogger().severe(messages.get("no-relay-token"));
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        configuredChats = config.getStringList("bot.chats").size();
        loadDisabledChats();
        loadSubscribed();
        loadChatNames();
        chats.clear();
        int skipped = 0;
        for (String id : config.getStringList("bot.chats")) {
            String trimmed = text(id);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (disabledChats.contains(trimmed)) {
                skipped++;
                continue;
            }
            chats.add(trimmed);
        }
        if (skipped > 0) {
            getLogger().info(messages.get("chats-skipped", skipped));
        }
        for (String id : subscribed) {
            if (!disabledChats.contains(id) && !chats.contains(id)) {
                chats.add(id);
            }
        }
        listenChats = new java.util.ArrayList<>(config.getStringList("bot.listen"));
        if (listenChats.isEmpty()) {
            // Слушаем весь список из конфига, а не только живые чаты: иначе игрок,
            // который когда-то заблокировал бота, после разблокировки пишет в пустоту —
            // его сообщения молча игнорируются, потому что чат выпал из рассылки.
            for (String id : config.getStringList("bot.chats")) {
                String trimmed = text(id);
                if (!trimmed.isEmpty()) {
                    listenChats.add(trimmed);
                }
            }
        }
        if (chats.isEmpty() && !MODE_SPOKE.equals(relayMode)) {
            getLogger().severe(messages.get("no-chats"));
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        formatFromMc = config.getString("format.from-minecraft", "%player%: %message%");
        formatToMc = config.getString("format.to-minecraft", "[TG] %sender%: %message%");
        pollTimeout = Math.max(5, Math.min(50, config.getInt("polling.timeout-seconds", 25)));
        messagesPerSecond = Math.max(1, Math.min(20, config.getInt("queue.messages-per-second", 3)));
        String empty = text(config.getString("delivery.when-empty", "")).toLowerCase(Locale.ROOT);
        whenEmpty = switch (empty) {
            case "wait", "hold" -> WHEN_WAIT;          // hold — старое имя того же режима
            case "skip", "echo", "drop" -> WHEN_SKIP;  // echo/drop — старые имена
            // ключа нет или он незнакомый: старое булево значение, true = держать
            default -> config.getBoolean("delivery.hold-when-empty", true) ? WHEN_WAIT : WHEN_SKIP;
        };
        holdMaxMinutes = Math.max(1, config.getInt("delivery.hold-max-minutes", 10));
        holdMaxMessages = Math.max(1, config.getInt("delivery.hold-max-messages", 20));

        muteEnabled = config.getBoolean("mute.enabled", true);
        muteEvents = !"chat".equalsIgnoreCase(config.getString("mute.scope", "all"));
        mutePrefix = config.getString("mute.prefix", "[mute] ");
        moderationChat = config.getString("moderation.chat", "");
        if (moderationChat == null) {
            moderationChat = "";
        }

        linkingRequired = config.getBoolean("linking.required", true);
        noticesEnabled = config.getBoolean("notices.enabled", true);
        noticesPrefix = config.getString("notices.prefix", "[TgRelay] ");
        pollFailureLimit = Math.max(1, config.getInt("notices.poll-failures", 5));

        // Мост поднимается один раз в onEnable: сокет и потоки на лету не переедут,
        // поэтому на reload честно говорим, что нужен перезапуск, а не делаем вид.
        if (!bridgeSignature.isEmpty() && !bridgeSignature.equals(bridgeSignature())) {
            getLogger().warning(messages.get("restart-required"));
        }
        return true;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    boolean isSpoke() {
        return MODE_SPOKE.equals(relayMode);
    }

    boolean isHub() {
        return MODE_HUB.equals(relayMode);
    }

    /** Всё, что нельзя поменять без перезапуска сервера. */
    private String bridgeSignature() {
        return String.join("|", relayMode, relayBind, relayToken, relayHub,
                String.join(",", relaySpokes), botToken);
    }

    /** Подставляет имя сервера: {@code %server%} в шаблонах сообщений. */
    private String withServer(String line) {
        if (line == null) {
            return null;
        }
        String result = line;
        if (!serverEmoji.isEmpty() && result.indexOf("%server_emoji%") >= 0) {
            result = result.replace("%server_emoji%", serverEmoji);
        }
        if (!serverName.isEmpty() && result.indexOf("%server%") >= 0) {
            result = result.replace("%server%", serverName);
        }
        return result;
    }

    /* ── Minecraft -> Telegram ── */

    /** Чат игрока. Если он заглушён — сообщение уйдёт только админам. */
    void relayChat(Player player, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String line = withServer(formatFromMc
                .replace("%player%", player.getName())
                .replace("%message%", message));
        deliver(player.getUniqueId(), line, true);
    }

    /** Событие игрока: вход, выход, смерть, достижение. */
    void relayEvent(Player player, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        deliver(player.getUniqueId(), withServer(line), muteEvents);
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
        String text = withServer(mutePrefix + line);
        if (isSpoke()) {
            // куда именно — решает главный сервер: у него канал модерации и привязки админов
            enqueueMuted(text);
            return;
        }
        List<String> targets = moderationTargets();
        if (targets.isEmpty()) {
            log(messages.get("muted-console", text));
            return;
        }
        enqueueTo(targets, text);
    }

    /** Главный сервер: строка от второго сервера. Заглушённые уходят в модерацию, не в общий чат. */
    private void acceptFromSpoke(String text, boolean muted) {
        if (!muted) {
            enqueue(text);
            return;
        }
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

    /** Сообщение сервера (say, объявление плагина) — в обычные чаты. */
    void relayBroadcast(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String text = withServer(line);
        enqueue(text);
        log(messages.get("broadcast-relayed", text));
    }

    /** Техническое сообщение админам: модерация в приоритете, иначе консоль. */
    private void notifyAdmins(String text) {
        if (!noticesEnabled || text == null || text.isBlank()) {
            return;
        }
        List<String> targets = moderationTargets();
        if (targets.isEmpty()) {
            log(messages.get("notices-console", text));
            return;
        }
        enqueueTo(targets, withServer(noticesPrefix + text));
    }

    /** Puts an already formatted line into the outbound queue (обычные чаты). */
    void enqueue(String text) {
        enqueueTo(null, text);
    }

    private void enqueueTo(List<String> targets, String text) {
        enqueueInternal(targets, text, false);
    }

    /** Второй сервер: пометить строку как заглушённую — адресата выбирает главный. */
    private void enqueueMuted(String text) {
        enqueueInternal(null, text, true);
    }

    private void enqueueInternal(List<String> targets, String text, boolean muted) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (text.length() > 4000) {
            text = text.substring(0, 4000);
        }
        if (!outbox.offer(new Outgoing(text, targets, muted))) {
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
                if (isSpoke()) {
                    sendToHub(item);
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
                            disabledChats.add(chat);
                            saveDisabledChats();
                            log(messages.get("chat-disabled", chatLabel(chat)));
                        } else if (item.targets != null) {
                            log(messages.get("admin-unreachable", chatLabel(chat)));
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

    /** Второй сервер: отдать строку главному. Три попытки, потом честно ругаемся в лог. */
    private void sendToHub(Outgoing item) {
        if (bridge == null) {
            log(messages.get("hub-unreachable", relayHub, "bridge is not running"));
            return;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                bridge.postEvent(item.text, item.muted);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                if (attempt == 3) {
                    log(messages.get("hub-unreachable", relayHub, String.valueOf(error)));
                    return;
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                List<TelegramApi.Update> updates = api.poll(offset.get() + 1, pollTimeout);
                if (pollFailures.get() != 0) {
                    pollFailures.set(0);
                    notifyAdmins(messages.get("telegram-up", serverName.isEmpty() ? "?" : serverName));
                }
                long highest = offset.get();
                for (TelegramApi.Update update : updates) {
                    highest = Math.max(highest, update.updateId);

                    if (tryLink(update)) {
                        continue;
                    }
                    if (linkingRequired && links.playerOf(update.fromId) == null) {
                        // без привязки некому верить: просим код из игры
                        askToLink(update.chatId);
                        continue;
                    }
                    if (!listenChats.contains(update.chatId)) {
                        continue;   // ignore chats we were not configured for
                    }

                    String line = withServer(formatToMc
                            .replace("%sender%", minecraftName(update.fromId, update.sender))
                            .replace("%message%", update.text));
                    if (isHub() && bridge != null) {
                        bridge.pushInbound(line);   // то же сообщение — на вторые серверы
                    }
                    showInGame(line, update.chatId);
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
                if (pollFailures.incrementAndGet() == pollFailureLimit) {
                    notifyAdmins(messages.get("telegram-down", pollFailureLimit,
                            String.valueOf(error.getMessage())));
                }
                try {
                    Thread.sleep(5000);   // back off instead of hammering Telegram
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Принимает строку из Telegram. Две вещи делаются независимо:
     *
     * <ol>
     *   <li><b>зеркало в фид</b> — сообщение всегда уходит в чаты бота (кроме чата автора),
     *       чтобы человек, которого нет в игре, видел всё в Telegram;</li>
     *   <li><b>показ в игре</b> — если есть кому показать, печатаем игрокам; если никого
     *       нет, работает {@code delivery.when-empty}: {@code hold} копит до входа игрока,
     *       {@code echo}/{@code drop} не копят.</li>
     * </ol>
     *
     * Второй сервер копию от главного не зеркалит и в буфер не кладёт: её уже обработал
     * главный сервер.
     *
     * @param sourceChat чат, откуда пришло сообщение; null — копия от главного сервера
     */
    private void showInGame(String line, String sourceChat) {
        boolean direct = sourceChat != null && !sourceChat.isBlank();

        if (direct) {
            List<String> targets = new ArrayList<>(chats);
            targets.remove(sourceChat);    // автору своё же сообщение не дублируем
            if (!targets.isEmpty()) {
                enqueueTo(targets, line);
                log(messages.get("mirrored", line));
            }
        }

        if (!getServer().getOnlinePlayers().isEmpty()) {
            deliverToGame(line);
            return;
        }
        if (!direct) {
            log(messages.get("spoke-copy-dropped", line));
            return;
        }
        if (WHEN_WAIT.equals(whenEmpty)) {
            hold(line);
            log(messages.get("held", line));
            return;
        }
        log(messages.get("dropped", line));
    }

    /**
     * Имя отправителя для игрового чата: ник из Minecraft, если телеграм-аккаунт
     * привязан к игроку; иначе то, что дал Telegram. Ник — главный источник правды,
     * иначе в игре человек называется не тем именем, которое видят остальные.
     */
    private String minecraftName(String telegramId, String fallback) {
        UUID player = links.playerOf(telegramId);
        if (player == null) {
            return fallback;
        }
        String name = null;
        Player online = getServer().getPlayer(player);
        if (online != null) {
            name = online.getName();
        }
        if (name == null || name.isBlank()) {
            try {
                name = getServer().getOfflinePlayer(player).getName();
            } catch (Exception ignored) {
                name = null;
            }
        }
        return (name == null || name.isBlank()) ? fallback : name;
    }

    /** Отдать строку всем, кто в игре. */
    private void deliverToGame(String line) {
        Component text = Component.text(line, NamedTextColor.GRAY);
        getServer().getScheduler().runTask(this, () -> {
            getServer().getOnlinePlayers().forEach(player -> player.sendMessage(text));
            log(messages.get("shown", line));
        });
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
        addSubscriber(update.chatId);
        enqueueTo(List.of(update.chatId), messages.get("link-welcome", name));
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


    /* ── команды ── */

    /** Подкоманды для TAB: игроку — свои, админу — все. */
    private static final List<String> PLAYER_SUBCOMMANDS =
            List.of("off", "on", "link", "unlink", "status");
    private static final List<String> ADMIN_SUBCOMMANDS =
            List.of("reload", "testmute", "chats");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String typed = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        List<String> result = new ArrayList<>();
        for (String sub : PLAYER_SUBCOMMANDS) {
            if (sub.startsWith(typed)) {
                result.add(sub);
            }
        }
        if (sender.hasPermission("tgrelay.admin")) {
            for (String sub : ADMIN_SUBCOMMANDS) {
                if (sub.startsWith(typed)) {
                    result.add(sub);
                }
            }
        }
        return result;
    }

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
                    refreshChatNames(true);
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
            case "chats":
                if (!requireAdmin(sender)) {
                    return true;
                }
                return listChats(sender);
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
        if (isSpoke() && bridge != null) {
            // бот живёт на главном сервере: код надо отдать туда, иначе он умрёт здесь
            bridge.postLinkCode(code, player.getUniqueId().toString(), player.getName());
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
        sender.sendMessage(Component.text(messages.get("status-mode",
                relayMode, serverName.isEmpty() ? "-" : serverName, bridgeWhere())));
        sender.sendMessage(Component.text(messages.get("moderation-status", moderationWhere())));
        if (disabledChatsCount() > 0) {
            sender.sendMessage(Component.text(
                    messages.get("disabled-status", disabledChatsCount(), configuredChats)));
        }
        return true;
    }

    /** С кем этот сервер разговаривает: адрес моста и число вторых серверов. */
    private String bridgeWhere() {
        if (isSpoke()) {
            return relayHub;
        }
        if (isHub()) {
            return bridge != null
                    ? messages.get("status-spokes", relayBind, bridge.spokeCount())
                    : relayBind;
        }
        return "-";
    }

    /** Список чатов: живые и отключённые, с именами из Telegram. */
    private boolean listChats(CommandSender sender) {
        sender.sendMessage(Component.text(messages.get("chats-header",
                chats.size(), configuredChats, disabledChats.size())));
        for (String chat : chats) {
            sender.sendMessage(Component.text(messages.get("chats-live", chatLabel(chat))));
        }
        for (String chat : new java.util.TreeSet<>(disabledChats)) {
            sender.sendMessage(Component.text(messages.get("chats-dead", chatLabel(chat))));
        }
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
        if (!isSpoke() && targets.isEmpty()) {
            sender.sendMessage(Component.text(messages.get("moderation-console")));
            return true;
        }
        if (isSpoke()) {
            relayMuted(messages.get("moderation-test-line"));
            sender.sendMessage(Component.text(messages.get("moderation-test-sent-hub")));
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
