package world.losos.tgrelay;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Message catalogue with a fallback chain: chosen language -> English -> the key itself.
 *
 * Bundled files live in the jar under {@code lang/<code>.yml}. A file with the same name
 * in {@code plugins/TgRelay/lang/} wins, so admins can reword messages without a rebuild.
 */
final class Messages {

    private final Map<String, String> values = new HashMap<>();

    Messages(TgRelay plugin, String language) {
        String code = (language == null || language.isBlank()) ? "en" : language.trim().toLowerCase();
        load(plugin, code, true);
        if (!"en".equals(code)) {
            load(plugin, "en", false);
        }
    }

    String language() {
        return values.getOrDefault("_language", "?");
    }

    private void load(TgRelay plugin, String code, boolean primary) {
        YamlConfiguration config = read(plugin, code);
        if (config == null) {
            if (primary) {
                plugin.getLogger().warning("No language file for '" + code + "', using English");
            }
            return;
        }
        values.put("_language", code);
        for (String key : config.getKeys(false)) {
            String value = config.getString(key, "");
            if (primary) {
                values.put(key, value);
            } else {
                values.putIfAbsent(key, value);
            }
        }
    }

    private YamlConfiguration read(TgRelay plugin, String code) {
        File external = new File(plugin.getDataFolder(), "lang" + File.separator + code + ".yml");
        if (external.isFile()) {
            return YamlConfiguration.loadConfiguration(external);
        }
        try (InputStream stream = plugin.getResource("lang/" + code + ".yml")) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception error) {
            plugin.getLogger().warning("Could not read lang/" + code + ".yml: " + error);
            return null;
        }
    }

    /** Returns the message, formatted with args when given. Unknown keys come back as-is. */
    String get(String key, Object... args) {
        String raw = values.get(key);
        if (raw == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return raw;
        }
        try {
            return String.format(raw, args);
        } catch (Exception ignored) {
            return raw;
        }
    }
}
