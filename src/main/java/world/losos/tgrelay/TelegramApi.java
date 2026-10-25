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

    /** Result of a delivery attempt: OK, chat is gone for good, or a temporary failure. */
    enum Result { OK, GONE, FAILED }

    static final class Update {
        final long updateId;
        final String chatId;
        final String sender;
        final String text;
        /** Telegram user id автора: по нему привязываем аккаунт к игроку. */
        final String fromId;

        Update(long updateId, String chatId, String sender, String text, String fromId) {
            this.updateId = updateId;
            this.chatId = chatId;
            this.sender = sender;
            this.text = text;
            this.fromId = fromId;
        }
    }

    private final HttpClient http;
    private final String token;
    private final Messages messages;

    TelegramApi(String token, Messages messages) {
        this.token = token;
        this.messages = messages;
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
            String fromId = "";
            if (from instanceof Map) {
                Object id = ((Map<?, ?>) from).get("id");
                if (id != null) {
                    fromId = String.valueOf(id);
                }
                // Одно имя в обе стороны: человеку в игре показываем имя из профиля,
                // а не логин @username — иначе в Telegram логин скрыт, а в игре виден.
                Object username = ((Map<?, ?>) from).get("username");
                Object firstName = ((Map<?, ?>) from).get("first_name");
                Object lastName = ((Map<?, ?>) from).get("last_name");
                String display = "";
                if (firstName instanceof String) {
                    display = ((String) firstName).trim();
                }
                if (lastName instanceof String && !((String) lastName).isBlank()) {
                    display = (display + " " + ((String) lastName).trim()).trim();
                }
                if (!display.isEmpty()) {
                    sender = display;
                } else if (username instanceof String) {
                    sender = "@" + username;
                }
            }

            String chatId = "";
            Object chat = message.get("chat");
            if (chat instanceof Map) {
                Object id = ((Map<?, ?>) chat).get("id");
                chatId = id == null ? "" : String.valueOf(id);
            }

            updates.add(new Update(updateId, chatId, sender, (String) text, fromId));
        }
        return updates;
    }

    /** Sends a message and tells the caller whether retrying this chat makes sense. */
    Result send(String chatId, String text) {
        String query = "chat_id=" + encode(chatId)
                + "&text=" + encode(text)
                + "&disable_web_page_preview=true"
                + "&disable_notification=true";
        try {
            Map<String, Object> answer = call("sendMessage", query, 15);
            if (answer != null && Boolean.TRUE.equals(answer.get("ok"))) {
                return Result.OK;
            }

            String description = answer == null ? "" : String.valueOf(answer.get("description"));
            Object code = answer == null ? null : answer.get("error_code");
            boolean forbidden = (code instanceof Number && ((Number) code).intValue() == 403)
                    || description.contains("bot was blocked")
                    || description.contains("chat not found")
                    || description.contains("user is deactivated")
                    || description.contains("bot is not a member");

            if (forbidden) {
                return Result.GONE;      // повторять бессмысленно: чат мёртв
            }
            TgRelay.log(messages.get("refused", description));
        } catch (Exception error) {
            TgRelay.log(messages.get("send-failed", String.valueOf(error)));
        }
        return Result.FAILED;
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
