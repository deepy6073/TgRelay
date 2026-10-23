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

/**
 * Game events that are worth forwarding to Telegram.
 *
 * Каждое событие проходит через {@code relayChat}/{@code relayEvent}: там проверяется,
 * не заглушил ли игрок свои сообщения, и если да — строка уходит админам с префиксом
 * {@code [mute]} вместо общего чата.
 */
public final class McEvents implements Listener {

    private final TgRelay plugin;

    McEvents(TgRelay plugin) {
        this.plugin = plugin;
    }

    private boolean enabled(String key) {
        return plugin.getConfig().getBoolean("events." + key, true);
    }

    private String template(String key, String fallback) {
        return plugin.getConfig().getString("events." + key + "-format", fallback);
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
