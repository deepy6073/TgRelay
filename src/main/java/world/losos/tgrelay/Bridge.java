package world.losos.tgrelay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Link between servers that share one Telegram bot.
 *
 * Telegram gives a bot exactly one {@code getUpdates} consumer, so two servers polling the
 * same token steal updates from each other. The way out is a hub: the server that owns the
 * bot keeps polling, and every other server sends its chat to the hub over the loopback
 * network instead of talking to Telegram at all.
 *
 * <pre>
 *   Telegram  &lt;-&gt;  hub (bot token, getUpdates)  &lt;-&gt;  spoke 1
 *                                               &lt;-&gt;  spoke 2
 * </pre>
 *
 * Both sides expose the same tiny HTTP endpoint, so the traffic is the same in both
 * directions: a JSON body {@code {"text": "...", "muted": false}} and a shared secret in the
 * {@value #TOKEN_HEADER} header. The hub only ever binds a loopback address unless the
 * administrator changes {@code relay.bind}.
 */
final class Bridge {

    static final String TOKEN_HEADER = "X-TgRelay-Token";

    /** Callbacks into the plugin; both run off the main thread. */
    interface Events {
        /** Hub: another server wants this line posted to Telegram. */
        void onSpokeEvent(String text, boolean muted);

        /** Spoke: the hub has a Telegram line that belongs in this server's chat. */
        void onInbound(String text);
    }

    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_LINE = 4000;
    private static final long WARN_INTERVAL_MS = 5 * 60_000L;

    private final Messages messages;
    private final Events events;
    private final String token;
    private final boolean spoke;
    private final List<URI> spokes;
    private final URI hub;
    private final HttpClient http;
    private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();

    private HttpServer server;
    private ExecutorService workers;
    private Self self;

    Bridge(Messages messages, boolean spoke, String token,
           List<String> spokeUrls, String hubUrl, Events events) {
        this.messages = messages;
        this.events = events;
        this.token = token == null ? "" : token.trim();
        this.spoke = spoke;
        this.spokes = new ArrayList<>();
        for (String url : spokeUrls) {
            URI uri = toUri(url);
            if (uri != null) {
                spokes.add(uri);
            }
        }
        this.hub = toUri(hubUrl);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Number of servers this hub talks to (0 on a spoke). */
    int spokeCount() {
        return spokes.size();
    }

    /**
     * Starts the listener. An empty address means "no listener": a hub without one can still
     * post its own chat, it just cannot hear the other servers.
     *
     * @return the address that is actually bound, or {@code null} when nothing was started
     */
    String listen(String bind) throws IOException {
        InetSocketAddress address = toAddress(bind);
        if (address == null) {
            return null;
        }
        server = HttpServer.create(address, 0);
        workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "TgRelay-bridge");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.createContext("/status", this::handleStatus);
        server.start();
        InetSocketAddress bound = server.getAddress();
        return bound.getAddress().getHostAddress() + ":" + bound.getPort();
    }

    /** Spoke: posts one game line to the hub. Throws when the hub did not take it. */
    void postEvent(String text, boolean muted) throws Exception {
        if (hub == null) {
            throw new IOException("relay.hub is not set");
        }
        String payload = "{\"text\":" + Json.quote(text) + ",\"muted\":" + muted + "}";
        HttpResponse<Void> response = http.send(request(hub, payload),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 300) {
            throw new IOException("hub answered HTTP " + response.statusCode());
        }
    }

    /** Hub: hands a Telegram line to every server that listens, without blocking the poller. */
    void pushInbound(String text) {
        String payload = "{\"text\":" + Json.quote(text) + "}";
        for (URI spokeUri : spokes) {
            http.sendAsync(request(spokeUri, payload), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            warn(spokeUri, String.valueOf(error.getCause() != null
                                    ? error.getCause() : error));
                        } else if (response.statusCode() >= 300) {
                            warn(spokeUri, "HTTP " + response.statusCode());
                        }
                    });
        }
    }

    /** Ответ на опрос «сколько у вас игроков»: имя сервера и число онлайн. */
    static final class Peer {
        final String name;
        final int players;

        Peer(String name, int players) {
            this.name = name;
            this.players = players;
        }
    }

    /** Что сервер отвечает на /status: его имя и сколько людей в игре. */
    interface Self {
        String name();

        int players();
    }

    void self(Self self) {
        this.self = self;
    }

    /**
     * Hub: спрашивает у каждого второго сервера, сколько у него игроков.
     * Нужно, чтобы «никого в игре» означало «никого ни на одном сервере».
     */
    List<Peer> peers() {
        List<Peer> result = new ArrayList<>();
        for (URI spokeUri : spokes) {
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(spokeUri.resolve("/status"))
                                .timeout(Duration.ofMillis(700))
                                .header(TOKEN_HEADER, token)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    continue;
                }
                Map<String, Object> json = Json.parseObject(response.body());
                Object name = json.get("name");
                Object players = json.get("players");
                result.add(new Peer(name instanceof String ? (String) name : spokeUri.toString(),
                        players instanceof Number ? ((Number) players).intValue() : 0));
            } catch (Exception ignored) {
                // сервер молчит — считаем, что игроков там нет
            }
        }
        return result;
    }

    void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        try {
            http.close();
        } catch (Exception ignored) {
            // older runtimes: the client closes itself when the plugin is unloaded
        }
    }

    /* ── the listener ── */

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String remote = String.valueOf(exchange.getRemoteAddress());
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405);
                return;
            }
            if (!authorised(exchange.getRequestHeaders().getFirst(TOKEN_HEADER))) {
                TgRelay.log(messages.get("bridge-auth", remote));
                respond(exchange, 401);
                return;
            }

            String body;
            try (InputStream input = exchange.getRequestBody()) {
                body = new String(input.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
            }

            String line = null;
            boolean muted = false;
            try {
                Map<String, Object> json = Json.parseObject(body);
                Object text = json.get("text");
                if (text instanceof String) {
                    line = ((String) text).trim();
                }
                muted = Boolean.TRUE.equals(json.get("muted"));
            } catch (RuntimeException broken) {
                TgRelay.log(messages.get("bridge-bad", remote, String.valueOf(broken)));
                respond(exchange, 400);
                return;
            }
            if (line == null || line.isEmpty()) {
                respond(exchange, 400);
                return;
            }
            if (line.length() > MAX_LINE) {
                line = line.substring(0, MAX_LINE);
            }

            if (spoke) {
                events.onInbound(line);
            } else {
                events.onSpokeEvent(line, muted);
            }
            respond(exchange, 204);
        } finally {
            exchange.close();
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        try {
            if (!authorised(exchange.getRequestHeaders().getFirst(TOKEN_HEADER))) {
                respond(exchange, 401);
                return;
            }
            int players = self == null ? 0 : self.players();
            String name = self == null ? "" : self.name();
            byte[] body = ("{\"players\":" + players + ",\"name\":" + Json.quote(name) + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private boolean authorised(String presented) {
        if (token.isEmpty() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                presented.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int code) throws IOException {
        exchange.sendResponseHeaders(code, -1);
        try (OutputStream ignored = exchange.getResponseBody()) {
            // empty body on purpose
        }
    }

    /** Keeps a broken spoke from filling the log — one line per server per five minutes. */
    private void warn(URI uri, String reason) {
        long now = System.currentTimeMillis();
        Long previous = lastWarn.put(uri.toString(), now);
        if (previous != null && now - previous < WARN_INTERVAL_MS) {
            return;
        }
        TgRelay.log(messages.get("spoke-unreachable", uri.toString(), reason));
    }

    private HttpRequest request(URI uri, String payload) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=utf-8")
                .header(TOKEN_HEADER, token)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private static URI toUri(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        try {
            URI uri = URI.create(value);
            return uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    /** "127.0.0.1:8765", ":8765" and "8765" all work; empty means "do not listen". */
    private static InetSocketAddress toAddress(String bind) {
        if (bind == null || bind.trim().isEmpty()) {
            return null;
        }
        String value = bind.trim();
        int colon = value.lastIndexOf(':');
        String host = colon > 0 ? value.substring(0, colon).trim() : "";
        String portText = colon >= 0 ? value.substring(colon + 1).trim() : value;
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException broken) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return host.isEmpty() ? new InetSocketAddress(port) : new InetSocketAddress(host, port);
    }
}
