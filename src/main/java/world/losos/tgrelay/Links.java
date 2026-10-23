package world.losos.tgrelay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Связки «telegram id -> UUID игрока».
 *
 * Нужны для одного: доставить заглушённое сообщение в личку админу. Телеграм не знает
 * игровых ников, сервер не знает телеграм-аккаунтов, связать их может только сам человек —
 * кодом из игры ({@code /tgrelay link}) или импортом старой базы TelegramChat.
 *
 * Файл: {@code plugins/TgRelay/linked.txt}, по строке на связку: {@code <tg id> <uuid>}.
 * Простой текст, а не JSON: его можно править руками, и он не ломается от лишней запятой.
 */
final class Links {

    private final Map<String, UUID> byTelegram = new ConcurrentHashMap<>();
    private Path file;

    void open(Path file) {
        this.file = file;
        load();
    }

    void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                try {
                    byTelegram.put(parts[0], UUID.fromString(parts[1]));
                } catch (IllegalArgumentException ignored) {
                    // битая строка — пропускаем, остальные связки важнее
                }
            }
        } catch (IOException error) {
            TgRelay.log("не смог прочитать linked.txt: " + error);
        }
    }

    void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, String> sorted = new java.util.TreeMap<>();
            byTelegram.forEach((tg, uuid) -> sorted.put(tg, uuid.toString()));
            List<String> lines = new ArrayList<>();
            sorted.forEach((tg, uuid) -> lines.add(tg + " " + uuid));
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException error) {
            TgRelay.log("не смог записать linked.txt: " + error);
        }
    }

    void put(String telegramId, UUID uuid) {
        byTelegram.put(telegramId, uuid);
        save();
    }

    /** Убирает все связки игрока (перепривязка или /tgrelay unlink). */
    void removePlayer(UUID uuid) {
        byTelegram.values().removeIf(uuid::equals);
        save();
    }

    /** Телеграм-адреса, привязанные к перечисленным игрокам. */
    List<String> telegramIdsOf(Collection<UUID> players) {
        List<String> result = new ArrayList<>();
        byTelegram.forEach((tg, uuid) -> {
            if (players.contains(uuid)) {
                result.add(tg);
            }
        });
        return result;
    }

    /** Сколько из перечисленных игроков имеют хотя бы одну привязку. */
    int linkedPlayers(Collection<UUID> players) {
        int count = 0;
        for (UUID uuid : players) {
            for (UUID linked : byTelegram.values()) {
                if (linked.equals(uuid)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    Map<String, UUID> snapshot() {
        return new LinkedHashMap<>(byTelegram);
    }

    int size() {
        return byTelegram.size();
    }

    static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
