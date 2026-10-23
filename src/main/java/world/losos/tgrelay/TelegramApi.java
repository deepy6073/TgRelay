package world.losos.tgrelay;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal Telegram Bot API client built on java.net.http.HttpClient.
 *
 * Why not plain HttpURLConnection like the old TelegramChat plugin: every request there
 * opened a socket and never closed it. On a long-running server that ends with thousands
 * of leaked connections and "No buffer space available" for everyone else on the machine.
 * HttpClient keeps a small pool, closes what it opened and reuses the rest.
 */
final class TelegramApi {

    static final class Update {
        final long updateId;
        final String chatId;
        final String sender;
        final String text;

        Update(long updateId, String chatId, String sender, String text) {
            this.updateId = updateId;
            this.chatId = chatId;
            this.sender = sender;
            this.text = text;
        }
    }

    private final HttpClient http;
    private final String token;

    TelegramApi(String token) {
        this.token = token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Long-polls for updates. Blocks up to timeoutSeconds plus network slack. */
    List<Update> poll(long offset, int timeoutSeconds) throws Exception {
        String query = "offset=" + offset + "&limit=20&timeout=" + timeoutSeconds;
        Map<String, Object> answer = call("getUpdates", query, timeoutSeconds + 10);
        List<Update> updates = new ArrayList<>();
        if (answer == null) {
            return updates;
        }

        Object result = answer.get("result");
        if (!(result instanceof List)) {
            return updates;
        }

        for (Object element : (List<?>) result) {
            if (!(element instanceof Map)) {
                continue;
            }
            Map<?, ?> update = (Map<?, ?>) element;
            long updateId = asLong(update.get("update_id"), 0);

            Object rawMessage = update.get("message");
            if (!(rawMessage instanceof Map)) {
                continue;
            }
            Map<?, ?> message = (Map<?, ?>) rawMessage;

            Object from = message.get("from");
            if (from instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) from).get("is_bot"))) {
                continue;
            }

            Object text = message.get("text");
            if (!(text instanceof String) || ((String) text).isBlank()) {
                continue;
            }

            String sender = "telegram";
            if (from instanceof Map) {
                Object username = ((Map<?, ?>) from).get("username");
                Object firstName = ((Map<?, ?>) from).get("first_name");
                if (username instanceof String) {
                    sender = "@" + username;
                } else if (firstName instanceof String) {
                    sender = (String) firstName;
                }
            }

            String chatId = "";
            Object chat = message.get("chat");
            if (chat instanceof Map) {
                Object id = ((Map<?, ?>) chat).get("id");
                chatId = id == null ? "" : String.valueOf(id);
            }

            updates.add(new Update(updateId, chatId, sender, (String) text));
        }
        return updates;
    }

    /** Sends a message. Returns false when Telegram rejected it (bad token, kicked from chat...). */
    boolean send(String chatId, String text) {
        String query = "chat_id=" + encode(chatId)
                + "&text=" + encode(text)
                + "&disable_web_page_preview=true"
                + "&disable_notification=true";
        try {
            Map<String, Object> answer = call("sendMessage", query, 15);
            if (answer != null && Boolean.TRUE.equals(answer.get("ok"))) {
                return true;
            }
            if (answer != null && answer.get("description") != null) {
                TgRelay.log("Telegram refused message: " + answer.get("description"));
            }
        } catch (Exception error) {
            TgRelay.log("sendMessage failed: " + error);
        }
        return false;
    }

    private Map<String, Object> call(String method, String query, int timeoutSeconds) throws Exception {
        URI uri = URI.create("https://api.telegram.org/bot" + token + "/" + method + "?" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "TgRelay/1.0")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = response.body();
        if (body == null || body.isEmpty()) {
            return null;
        }
        return Json.parseObject(body);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
