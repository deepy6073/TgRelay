# SpigotMC / Modrinth listing draft

Готовый текст для публикации ресурса на SpigotMC (BBCode) и Modrinth (Markdown).
Ссылки и цифры сверить перед публикацией.

---

## SpigotMC

**Title (≤ 55):**
```
TgRelay — Minecraft ↔ Telegram chat, no leaked sockets
```

**Tagline:**
```
Two-way Telegram bridge: long polling, a rate-limited queue, player self-mute, several servers on one bot
```

**Category:** Chat
**Native Minecraft Version:** 1.21.8 (tested up to 26.1.2)
**Tested Minecraft Versions:** 1.21 – 1.21.11, 26.1 – 26.1.2
**Source code:** https://github.com/deepy6073/TgRelay
**Donation:** none

**Description (BBCode):**

```bbcode
[IMG]https://raw.githubusercontent.com/deepy6073/TgRelay/main/docs/img/hero.png[/IMG]

[B][SIZE=5]TgRelay — Minecraft ↔ Telegram, without the socket leak[/SIZE][/B]

Paper plugin that connects your server chat to a Telegram group, channel or DM — and does it without leaking a network socket every second.

It was written for [URL=https://map.losos.world/]Losos World[/URL], a survival server running since 2014, after the classic TelegramChat plugin pushed the machine into "No buffer space available" with thousands of leaked connections.

[B][SIZE=4]Why another Telegram bridge?[/SIZE][/B]

The old TelegramChat plugin calls [FONT=Courier New]getUpdates[/FONT] in a tight loop and leaves every socket behind — about 2.3 new connections per second, several thousand per hour. Windows eventually runs out of ephemeral ports and everything else on the machine starts failing: Bedrock players get dropped, RCON stalls, plugins cannot reach Mojang.

TgRelay does it properly:

[LIST]
[*][B]Long polling[/B] — a single getUpdates?timeout=25 request stays open, so one cycle costs one connection instead of hundreds.
[*][B]One shared java.net.http.HttpClient[/B] with a bounded pool: connections are reused and closed on shutdown.
[*][B]Outbound queue with a rate limit[/B] — a chatty server cannot flood Telegram and get the bot throttled.
[*][B]Backoff on errors[/B] instead of a retry storm; the update offset survives restarts, so old messages are never replayed into the game.
[*][B]Several servers on one bot[/B] — a hub/spoke bridge, so a survival world and a creative server report into the same chat, each line prefixed with its server name.
[*][B]No third-party libraries[/B] — only the JDK and the Paper API.
[/LIST]

[IMG]https://raw.githubusercontent.com/deepy6073/TgRelay/main/docs/img/architecture.png[/IMG]

[B][SIZE=4]Features[/SIZE][/B]

[LIST]
[*]Minecraft → Telegram: chat, join, quit, deaths and advancements (each event can be disabled).
[*]Telegram → Minecraft: messages are printed with tellraw, so they keep colours and are never attributed to a player.
[*]Groups, channels and private chats; one bot can post to many destinations.
[*]%player%, %message%, %sender%, %server% placeholders in every format string.
[*]English, Russian and Chinese locale files, overridable without a rebuild.
[*]/tgrelay status shows chats, queue, offset and the moderation route; /tgrelay reload re-reads the config.
[/LIST]

[B][SIZE=4]Two servers, one Telegram chat[/SIZE][/B]

Telegram gives a bot exactly one getUpdates reader, so installing the plugin twice with the same token does not work — the two pollers steal messages from each other and one server's chat randomly disappears. The usual advice is "run a second bot", which is a second chat in every player's list.

TgRelay instead makes one server the hub: it owns the bot, and every other server reports to it over loopback HTTP with a shared secret.

[LIST]
[*][B]hub[/B] — owns the token, polls Telegram, accepts lines from the other servers and pushes each Telegram message back to all of them;
[*][B]spoke[/B] — no token of its own: sends its chat to the hub and prints what comes back;
[*][B]standalone[/B] — the default, one server, nothing to configure.
[/LIST]

[CODE]
# hub
server: { name: "Losos" }
relay:
  mode: hub
  token: "a-long-random-string"
  bind: "127.0.0.1:8765"
  spokes: ["http://127.0.0.1:8766"]
format:
  from-minecraft: "[%server%] %player%: %message%"

# spoke
server: { name: "Build" }
relay:
  mode: spoke
  token: "a-long-random-string"
  bind: "127.0.0.1:8766"
  hub: "http://127.0.0.1:8765"
[/CODE]

Result: [FONT=Courier New][Losos] Steve: hi[/FONT] and [FONT=Courier New][Build] Alex: testing the roof[/FONT] in one chat. Muted players are still routed to the moderation channel by the hub, and /tgrelay status on each side shows the mode, the server name and the peer address.

[B][SIZE=4]Privacy: players can mute themselves[/SIZE][/B]

[IMG]https://raw.githubusercontent.com/deepy6073/TgRelay/main/docs/img/mute.png[/IMG]

A player can keep their own messages out of the public chats:

[LIST]
[*][FONT=Courier New]/tgrelay off[/FONT] — the player's messages stop going to the configured chats;
[*][FONT=Courier New]/tgrelay on[/FONT] — back to normal;
[*][FONT=Courier New]/tgrelay status[/FONT] — what their current state is.
[/LIST]

Muted messages are [B]not deleted[/B]. They are forwarded to a moderation channel with a [FONT=Courier New][mute][/FONT] prefix, so admins keep the full picture while everyone else sees nothing:

[LIST=1]
[*][FONT=Courier New]moderation.chat[/FONT] — a separate Telegram chat the players are not in (simplest: add the bot there, paste the id, done);
[*]otherwise a DM to every operator with a linked account — operators come from ops.json, so there is no second list to maintain;
[*]otherwise the server console, so nothing disappears silently.
[/LIST]

Linking is a one-time code ([FONT=Courier New]/tgrelay link[/FONT]), because Telegram knows user ids while Minecraft knows nicknames — a stranger cannot claim somebody else's account by typing a nickname.

[IMG]https://raw.githubusercontent.com/deepy6073/TgRelay/main/docs/img/commands.png[/IMG]

[B][SIZE=4]Install[/SIZE][/B]

[LIST=1]
[*]Drop TgRelay.jar into plugins/.
[*]Start the server once, then edit plugins/TgRelay/config.yml — bot token and the chats to post to.
[*]Run /tgrelay reload.
[/LIST]

For a group, add the bot to the group first — otherwise Telegram answers "chat not found", which is exactly the failure that made the old plugin spin.

[B][SIZE=4]Commands[/SIZE][/B]

[LIST]
[*][FONT=Courier New]/tgrelay off | on[/FONT] — players: hide or restore your own messages
[*][FONT=Courier New]/tgrelay link | unlink[/FONT] — players: pair a Telegram account
[*][FONT=Courier New]/tgrelay status[/FONT] — your state; admins also see the relay counters
[*][FONT=Courier New]/tgrelay reload[/FONT] — tgrelay.admin: re-read config.yml
[*][FONT=Courier New]/tgrelay testmute[/FONT] — tgrelay.admin: send a test message through the moderation route
[/LIST]

[B][SIZE=4]Compatibility[/SIZE][/B]

[LIST]
[*]Paper and forks that keep the Paper API (Purpur and similar) — the plugin uses AsyncChatEvent.
[*]Java 21+, tested on Paper 1.21 and on a live server running 26.1.2 with Geyser, ViaVersion and Floodgate.
[*]Can run side by side with TelegramChat, but only one plugin may hold a bot token at a time: Telegram allows a single getUpdates consumer per bot.
[/LIST]

[B][SIZE=4]Open to ideas[/SIZE][/B]

The plugin is maintained in the open and shaped by the people who run it. If something is missing — a different relay target, per-world filters, a locale you need — open an issue or a pull request on GitHub. Bug reports with a log excerpt are especially welcome.

[B][SIZE=4]Links[/SIZE][/B]

[LIST]
[*]Source code and releases: [URL=https://github.com/deepy6073/TgRelay]github.com/deepy6073/TgRelay[/URL]
[*]Hangar: [URL=https://hangar.papermc.io/Stil00000/TgRelay]hangar.papermc.io/Stil00000/TgRelay[/URL]
[*]License: MIT
[*]Built for [URL=https://losos.world/]Losos World[/URL] — a no-wipe survival server since 2014 ([URL=https://map.losos.world/]live map[/URL])
[/LIST]
```

---

## Modrinth

**Summary:**
```
Two-way Minecraft ↔ Telegram bridge for Paper: one long-poll connection, a rate-limited queue, player self-mute, and several servers on one bot.
```

**Body:** тот же текст, что на Hangar (`README`/страница проекта), картинки те же.

---

## Анонс в Telegram (для чата сервера)

```
Плагин, который держит наш мост с Telegram, теперь есть на Hangar:
https://hangar.papermc.io/Stil00000/TgRelay

Что внутри: одна long-poll сессия вместо тысяч сокетов, лимит на отправку,
/tgrelay off — можно убрать свои сообщения из общего чата (админы при этом всё видят),
и один бот на оба сервера: сообщения из тестового теперь тоже здесь, с префиксом [Build].

Ставьте звёзды и присылайте идеи — плагин открытый.
```
