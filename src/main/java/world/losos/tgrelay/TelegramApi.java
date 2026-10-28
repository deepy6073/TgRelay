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
    /** Плашка из пака: резервный эмодзи и плитки по порядку. */
    static final class Badge {
        final String fallback;
        final List<String> tiles;

        Badge(String fallback, List<String> tiles) {
            this.fallback = fallback;
            this.tiles = List.copyOf(tiles);
        }
    }

    /** Имя плашки → её плитки. В тексте пишется как {имя}. */
    private volatile Map<String, Badge> badges = Map.of();
    /** Ник → id головы игрока. В тексте пишется как {avatar:ник}. */
    private volatile Map<String, String> avatars = Map.of();
    /** Чем заменять голову, если её ещё нет (одиночный эмодзи). */
    private volatile String avatarFallback = "";

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
    void badges(Map<String, Badge> badges) {
        this.badges = badges == null ? Map.of() : Map.copyOf(badges);
    }

    void avatars(Map<String, String> avatars, String fallback) {
        this.avatars = avatars == null ? Map.of() : Map.copyOf(avatars);
        this.avatarFallback = fallback == null ? "" : fallback;
    }

    /**
     * Разворачивает {имя} в ленту плиток: одна плашка = несколько кастомных эмодзи подряд,
     * поэтому в тексте остаётся столько резервных символов, сколько плиток.
     * Возвращает пару: новый текст и JSON сущностей (либо null).
     * Смещения считаются в единицах UTF-16 — так их ждёт Bot API.
     */
    private String[] expand(String text) {
        Map<String, Badge> known = badges;
        if (text == null || text.isEmpty() || known.isEmpty() || text.indexOf('{') < 0) {
            return new String[] {text, null};
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        List<String> entities = new ArrayList<>();
        int offset = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{') {
                int close = text.indexOf('}', i);
                if (close > i) {
                    String token = text.substring(i + 1, close);
                    if (token.startsWith("avatar:")) {
                        String nick = token.substring(7);
                        String id = avatars.get(nick);
                        i = close + 1;
                        if (id == null || id.isEmpty() || avatarFallback.isEmpty()) {
                            continue;                       // головы пока нет — без плашки
                        }
                        if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                            out.append("  ");
                            offset += 2;
                        }
                        int units = Character.charCount(avatarFallback.codePointAt(0));
                        out.append(avatarFallback);
                        entities.add("{\"type\":\"custom_emoji\",\"offset\":" + offset
                                + ",\"length\":" + units
                                + ",\"custom_emoji_id\":\"" + id + "\"}");
                        offset += units;
                        while (i < text.length() && text.charAt(i) == ' ') {
                            i += 1;
                        }
                        if (i < text.length()) {
                            out.append("  ");
                            offset += 2;
                        }
                        continue;
                    }
                    Badge badge = known.get(token);
                    if (badge != null) {
                        // плашка — картинка, тексту нужен зазор с обеих сторон
                        if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                            out.append("  ");
                            offset += 2;
                        }
                        for (String tile : badge.tiles) {
                            int units = badge.fallback.codePointCount(0, badge.fallback.length()) == 1
                                    ? Character.charCount(badge.fallback.codePointAt(0))
                                    : badge.fallback.length();
                            out.append(badge.fallback);
                            entities.add("{\"type\":\"custom_emoji\",\"offset\":" + offset
                                    + ",\"length\":" + units
                                    + ",\"custom_emoji_id\":\"" + tile + "\"}");
                            offset += units;
                        }
                        i = close + 1;
                        while (i < text.length() && text.charAt(i) == ' ') {
                            i += 1;                       // свои пробелы в шаблоне съедаем
                        }
                        if (i < text.length()) {
                            out.append("  ");
                            offset += 2;
                        }
                        continue;
                    }
                }
            }
            out.append(c);
            offset += 1;
            i += 1;
        }
        return new String[] {out.toString(), entities.isEmpty() ? null : "[" + String.join(",", entities) + "]"};
    }

    /**
     * Добавляет голову игрока в пак аватарок и возвращает её custom_emoji_id.
     * Пак создан ботом, но принадлежит владельцу — его id и нужен в user_id.
     */
    /** Ищет id только что добавленной головы: по ключевому слову с ником, иначе последнюю. */
    private String findStickerId(String pack, String nick) throws Exception {
        Map<String, Object> answer = call("getStickerSet", "name=" + encode(pack), 15);
        if (answer == null || !Boolean.TRUE.equals(answer.get("ok"))) {
            return null;
        }
        Object result = answer.get("result");
        if (!(result instanceof Map)) {
            return null;
        }
        Object stickersRaw = ((Map<?, ?>) result).get("stickers");
        if (!(stickersRaw instanceof List)) {
            return null;
        }
        String last = null;
        for (Object element : (List<?>) stickersRaw) {
            if (!(element instanceof Map)) {
                continue;
            }
            Map<?, ?> sticker = (Map<?, ?>) element;
            Object idRaw = sticker.get("custom_emoji_id");
            if (idRaw == null) {
                continue;
            }
            String id = String.valueOf(idRaw);
            last = id;
            Object keywords = sticker.get("keywords");
            if (keywords instanceof List && ((List<?>) keywords).contains(nick)) {
                return id;
            }
        }
        return last;
    }

    String addAvatarSticker(String pack, long ownerId, String nick, byte[] png, String fallback) {
        String boundary = "----tgrelay" + Long.toHexString(System.nanoTime());
        StringBuilder head = new StringBuilder();
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"user_id\"\r\n\r\n")
                .append(ownerId).append("\r\n");
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"name\"\r\n\r\n")
                .append(pack).append("\r\n");
        String sticker = "{\"sticker\":\"attach://head\",\"format\":\"static\",\"emoji_list\":[\""
                + fallback + "\"],\"keywords\":[\"" + nick + "\"]}";
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"sticker\"\r\n\r\n")
                .append(sticker).append("\r\n");
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"head\"; filename=\"head.png\"\r\n")
                .append("Content-Type: image/png\r\n\r\n");

        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headBytes.length + png.length + tail.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(png, 0, body, headBytes.length, png.length);
        System.arraycopy(tail, 0, body, headBytes.length + png.length, tail.length);

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/addStickerToSet"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> answer = Json.parseObject(response.body());
            if (answer != null && Boolean.TRUE.equals(answer.get("ok"))) {
                // addStickerToSet отвечает просто true, поэтому id ищем в наборе
                String id = findStickerId(pack, nick);
                if (id != null) {
                    return id;
                }
            }
            TgRelay.log(messages.get("avatar-refused", nick, response.body()));
        } catch (Exception error) {
            TgRelay.log(messages.get("avatar-failed", nick, String.valueOf(error)));
        }
        return null;
    }

    Result send(String chatId, String text) {
        String[] expanded = expand(text);
        StringBuilder builder = new StringBuilder("chat_id=").append(encode(chatId))
                .append("&text=").append(encode(expanded[0]))
                .append("&disable_web_page_preview=true")
                .append("&disable_notification=true");
        if (expanded[1] != null) {
            builder.append("&entities=").append(encode(expanded[1]));
        }
        String query = builder.toString();
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

    /**
     * Имя чата для логов и /tgrelay chats: заголовок группы, имя человека или @username.
     * Возвращает пустую строку, если Telegram не ответил.
     */
    String chatName(String chatId) {
        try {
            Map<String, Object> answer = call("getChat", "chat_id=" + encode(chatId), 10);
            if (answer == null || !Boolean.TRUE.equals(answer.get("ok"))) {
                return "";
            }
            Object result = answer.get("result");
            if (!(result instanceof Map)) {
                return "";
            }
            Map<?, ?> chat = (Map<?, ?>) result;
            Object title = chat.get("title");
            if (title instanceof String && !((String) title).isBlank()) {
                return ((String) title).trim();
            }
            String name = "";
            Object first = chat.get("first_name");
            if (first instanceof String) {
                name = ((String) first).trim();
            }
            Object last = chat.get("last_name");
            if (last instanceof String && !((String) last).isBlank()) {
                name = (name + " " + ((String) last).trim()).trim();
            }
            Object username = chat.get("username");
            if (username instanceof String && !((String) username).isBlank()) {
                name = name.isEmpty() ? "@" + username : name + " (@" + username + ")";
            }
            return name;
        } catch (Exception error) {
            return "";
        }
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
