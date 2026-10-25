# TgRelay

Minecraft chat ↔ Telegram bot, without the connection leak.

[![Hangar](https://img.shields.io/badge/Hangar-TgRelay-86d15c)](https://hangar.papermc.io/Stil00000/TgRelay)
[![License: MIT](https://img.shields.io/badge/license-MIT-86d15c)](LICENSE)

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
* **several servers on one bot** — a hub/spoke bridge, so a second server reports into the same chat with its own prefix (below);
* no third-party HTTP libraries — only the JDK and the server API.

## Install

1. Download `TgRelay.jar` from [Hangar](https://hangar.papermc.io/Stil00000/TgRelay) or the [GitHub releases](https://github.com/deepy6073/TgRelay/releases) and drop it into `plugins/`.
2. Start the server once, then edit `plugins/TgRelay/config.yml`:

```yaml
bot:
  token: "123456:AA..."       # from @BotFather
  chats:
    - 123456789               # your DM with the bot
    - -1001234567890          # a group the bot is a member of
```

3. `/tgrelay reload`.

## Several servers, one bot

A survival world plus a creative build server is a normal setup — and one Telegram chat for both is what players usually want. It cannot be done by installing the plugin twice with the same token: Telegram gives a bot exactly one `getUpdates` reader, so two pollers steal messages from each other and one server's chat randomly disappears.

TgRelay solves it with a hub. One server owns the bot; the rest talk to it over loopback HTTP with a shared secret, so no second token and no second bot in the chat:

```
                    ┌──────────────┐
   Telegram  ◄─────►│ hub: Losos   │  owns the token, polls, posts
                    └──────┬───────┘
                           │ 127.0.0.1:8765 ⇄ 127.0.0.1:8766
                    ┌──────┴───────┐
                    │ spoke: Build │  no token, no polling
                    └──────────────┘
```

```yaml
# hub (the server that owns the bot)
server: { name: "Losos" }
relay:
  mode: hub
  token: "a-long-random-string"
  bind: "127.0.0.1:8765"
  spokes: ["http://127.0.0.1:8766"]
format:
  from-minecraft: "[%server%] %player%: %message%"
```

```yaml
# spoke (any other server, same token)
server: { name: "Build" }
relay:
  mode: spoke
  token: "a-long-random-string"
  bind: "127.0.0.1:8766"
  hub: "http://127.0.0.1:8765"
```

Everything else is unchanged: `/tgrelay off`, `link`, `reload` and the moderation channel work on every server, and muted messages are routed by the hub. Full reference: **[docs/configuration.md](docs/configuration.md#relay--several-servers-one-bot)**.

For a group, add the bot to the group first, otherwise Telegram answers
`chat not found` — exactly the failure that made the old plugin spin.

## Commands

| Command | Who | What it does |
|---|---|---|
| `/tgrelay off` | any player | your messages stop going to `bot.chats` |
| `/tgrelay on` | any player | back to normal |
| `/tgrelay link` | any player | get a one-time code, send it to the bot in a DM — links your Telegram account |
| `/tgrelay unlink` | any player | drop the link |
| `/tgrelay status` | any player | your own state; admins also see the relay counters |
| `/tgrelay reload` | `tgrelay.admin` | re-read config.yml |
| `/tgrelay testmute` | `tgrelay.admin` | send a test message through the moderation route |

### Self-mute and the moderation channel

A player can take their own messages out of the public chats. They are **not deleted**:
they go to the moderation channel with a `[mute]` prefix, so admins keep the full picture
while everyone else sees nothing.

Two ways to receive them, `moderation.chat` wins when it is set:

```yaml
moderation:
  # 1. a separate Telegram chat the players are not in — one line, no linking at all
  chat: "-1001234567890"
```

With `moderation.chat` empty, the plugin falls back to a **DM to every operator** that linked
their account with `/tgrelay link`. Operators come from the server's `ops.json` — there is no
separate admin list to maintain, and losing op stops the DMs automatically.

If there is neither a moderation chat nor a linked operator, muted messages are written to the
server console only, so nothing is lost silently.

Why linking exists at all: Telegram knows a user's numeric id and `@username`, Minecraft knows
nicknames and UUIDs, and nothing connects the two. A one-time code (`/tgrelay link`) is the
smallest safe bridge — a stranger cannot claim somebody else's account by typing a nickname.

```yaml
mute:
  enabled: true     # false — the command is refused
  scope: all        # all = chat and player events, chat = chat only
  prefix: "[mute] "
```

State files: `plugins/TgRelay/muted.txt` (who muted themselves) and `linked.txt`
(`<telegram id> <uuid>`), both plain text and safe to edit by hand.

The plugin also imports the `linkedChats` map from an old `plugins/TelegramChat/data.json`
on first start — but only for players that are operators, and only if `linked.txt` is empty.
Nothing else from the old plugin is read.

## Configuration

Every key in `plugins/TgRelay/config.yml` is documented in
**[docs/configuration.md](docs/configuration.md)** — bot token and destinations, formats,
events, polling, queue, delivery, self-mute and the moderation channel, plus ready-made recipes.

## Build

No Gradle/Maven needed — the Paper server jar already contains the whole API:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 -ServerRoot "C:\path\to\server"
```

Requires JDK 21+.

## Notes

* Paper / Purpur (uses `AsyncChatEvent`). Spigot's legacy `AsyncPlayerChatEvent` is not wired.
* Telegram → Minecraft messages are printed with `tellraw`, so they keep colours and are not
  attributed to any player.
* Only chats listed in `bot.chats` are accepted for the Telegram → Minecraft direction.
* Muted players are muted in the *outbound* direction only — their messages still appear in the
  game chat for everyone, they just do not reach the public Telegram chats.
* Nobody online: the message is posted into the bot chats right away (`delivery.when-empty: echo`)
  instead of waiting for a player who may never come. `hold` keeps the old behaviour — silence
  and a buffer until someone joins; `drop` throws it away.

## Related

* [mastercake10/TelegramChat](https://github.com/mastercake10/TelegramChat) — the plugin that
  started this genre, and the source of the incident described above.
* [SpigotMC: TelegramChat](https://www.spigotmc.org/resources/telegramchat.16576/) — its resource page.

## Authors

* **Deepy** — code, build, docs. `<deepy6073@gmail.com>`
* **[Stil-ru](https://github.com/Stil-ru)** — the server this was written for: the incident that
  exposed the leak, production testing on a live 2014 world, and the idea of publishing it.

MIT licensed.
