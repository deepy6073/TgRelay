package world.losos.tgrelay;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Game events that are worth forwarding to Telegram.
 *
 * Каждое событие проходит через {@code relayChat}/{@code relayEvent}: там проверяется,
 * не заглушил ли игрок свои сообщения, и если да — строка уходит админам с префиксом
 * {@code [mute]} вместо общего чата.
 */
public final class McEvents implements Listener {

    private final TgRelay plugin;
    /** Защита от двойной отправки: одно и то же сообщение может прийти двумя событиями. */
    private String lastBroadcast = "";
    private long lastBroadcastAt;

    McEvents(TgRelay plugin) {
        this.plugin = plugin;
    }

    private void relayServerMessage(String text) {
        if (!enabled("broadcast") || text == null) {
            return;
        }
        String clean = text.replaceAll("(?i)\u00A7[0-9A-FK-OR]", "").trim();
        if (clean.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (clean.equals(lastBroadcast) && now - lastBroadcastAt < 3000) {
            return;
        }
        lastBroadcast = clean;
        lastBroadcastAt = now;
        plugin.relayBroadcast(template("broadcast", "[%server%] %message%").replace("%message%", clean));
    }

    /** {@code say ...} из консоли сервера. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        relaySay(event.getCommand());
    }

    /** {@code say ...} по RCON. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRemoteCommand(RemoteServerCommandEvent event) {
        relaySay(event.getCommand());
    }

    private void relaySay(String command) {
        if (command == null) {
            return;
        }
        String trimmed = command.trim();
        if (trimmed.length() < 5 || !trimmed.regionMatches(true, 0, "say ", 0, 4)) {
            return;
        }
        relayServerMessage(trimmed.substring(4));
    }

    private boolean enabled(String key) {
        return plugin.getConfig().getBoolean("events." + key, true);
    }

    private String template(String key, String fallback) {
        return plugin.getConfig().getString("events." + key + "-format", fallback);
    }

    /**
     * Сообщения сервера: {@code say} из консоли и RCON, объявления плагинов — всё, что идёт
     * через broadcast. Так в Telegram попадает «сервер перезапустится через минуту».
     * Наша собственная рассылка в игре идёт напрямую игрокам, а не через broadcast,
     * поэтому петли нет.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroadcast(BroadcastMessageEvent event) {
        relayServerMessage(event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled("chat")) {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.relayChat(event.getPlayer(), text);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // если в игре никого не было, придержанные сообщения из Telegram отдаём сейчас
        plugin.flushPending();

        if (!enabled("join")) {
            return;
        }
        plugin.relayEvent(event.getPlayer(), template("join", "%player% joined the game")
                .replace("%player%", event.getPlayer().getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!enabled("quit")) {
            return;
        }
        plugin.relayEvent(event.getPlayer(), template("quit", "%player% left the game")
                .replace("%player%", event.getPlayer().getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled("death")) {
            return;
        }
        String death = event.deathMessage() == null
                ? event.getEntity().getName() + " died"
                : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        plugin.relayEvent(event.getEntity(), template("death", "%message%").replace("%message%", death));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!enabled("advancement")) {
            return;
        }
        String key = event.getAdvancement().getKey().getKey();
        if (key.startsWith("recipes/")) {
            return;   // too noisy, every crafted item
        }
        plugin.relayEvent(event.getPlayer(), template("advancement", "%player% has made the advancement %advancement%")
                .replace("%player%", event.getPlayer().getName())
                .replace("%advancement%", key));
    }
}
