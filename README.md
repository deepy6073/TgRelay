# TgRelay

Minecraft chat ↔ Telegram bot, without the connection leak.

## From Losos World

This plugin was written for **[Losos World](https://map.losos.world/)** — a survival Minecraft server
that has been running since 2014 without a single wipe, without private zones and without donations.
Java and Bedrock, `mc.losos.world`. The live map is at **[map.losos.world](https://map.losos.world/)**.

The relay exists because that server ran into the problem described below — on a real world
with real players, not in a benchmark.

## Why another relay?

The classic [`TelegramChat`](https://github.com/mastercake10/TelegramChat) plugin (1.0.21, December 2022,
last release; [SpigotMC page](https://www.spigotmc.org/resources/telegramchat.16576/)) calls `getUpdates`
in a tight loop and leaves every socket behind. On a busy or quiet server alike it leaks
about **two connections per second** — several thousand per hour. Windows runs out of
ephemeral ports, and then *everything else* on the machine starts failing with
`No buffer space available (maximum connections reached?)`: Bedrock players get dropped,
RCON stalls, plugins cannot reach Mojang.

**This is not a fork of it.** No code, classes or resources were taken from TelegramChat — the
plugin was written from scratch against the modern Paper API. What is borrowed is the diagnosis
(and the fact that a chat relay with long polling is still useful in 2026), not the implementation.
The two projects can be installed side by side; only one of them should hold a bot token at a time,
because Telegram allows a single `getUpdates` consumer per bot.

TgRelay does it properly:

* **long polling** — one `getUpdates?timeout=25` request stays open instead of hundreds per minute;
* **one shared `java.net.http.HttpClient`** with a bounded pool, so connections are reused and closed;
* **outbound queue** with a rate limit, so a chatty server cannot flood Telegram and get the bot throttled;
* **backoff on errors** instead of a retry storm;
* no third-party HTTP libraries — only the JDK and the server API.

## Install

1. Drop `TgRelay.jar` into `plugins/`.
2. Start the server once, then edit `plugins/TgRelay/config.yml`:

```yaml
bot:
  token: "123456:AA..."       # from @BotFather
  chats:
    - 123456789               # your DM with the bot
    - -1001234567890          # a group the bot is a member of
```

3. `/tgrelay reload`.

For a group, add the bot to the group first, otherwise Telegram answers
`chat not found` — exactly the failure that made the old plugin spin.

## Commands

| Command | What it does |
|---|---|
| `/tgrelay status` | chats, queue size, current update offset |
| `/tgrelay reload` | re-read config.yml |

Permission: `tgrelay.admin` (default: op).

## Build

No Gradle/Maven needed — the Paper server jar already contains the whole API:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 -PaperJar "C:\path\to\paper-1.21.8.jar"
```

Requires JDK 21+.

## Notes

* Paper / Purpur (uses `AsyncChatEvent`). Spigot's legacy `AsyncPlayerChatEvent` is not wired.
* Telegram → Minecraft messages are printed with `tellraw`, so they keep colours and are not
  attributed to any player.
* Only chats listed in `bot.chats` are accepted for the Telegram → Minecraft direction.

## Related

* [mastercake10/TelegramChat](https://github.com/mastercake10/TelegramChat) — the plugin that
  started this genre, and the source of the incident described above.
* [SpigotMC: TelegramChat](https://www.spigotmc.org/resources/telegramchat.16576/) — its resource page.

## Authors

* **Deepy** — code, build, docs. `<deepy6073@gmail.com>`
* **[Stil-ru](https://github.com/Stil-ru)** — the server this was written for: the incident that
  exposed the leak, production testing on a live 2014 world, and the idea of publishing it.

MIT licensed.
