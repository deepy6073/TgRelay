# Configuration

Everything lives in `plugins/TgRelay/config.yml`. After editing, run `/tgrelay reload` — no restart needed, except for the bridge keys listed in [`relay`](#relay--several-servers-one-bot).

The file is created on first start from the defaults baked into the jar. Unknown keys are ignored; missing keys fall back to the defaults listed below.

---

## `language`

```yaml
language: en
```

Interface language for everything the plugin prints: `en`, `ru`, `zh`. Bundled in the jar; override a single locale by dropping your own `plugins/TgRelay/lang/<code>.yml` next to the config.

---

## `server`

```yaml
server:
  name: ""
```

The name of *this* server. It does nothing on its own: it is the value that `%server%` expands to in the formats below, so one Telegram chat can tell several servers apart.

```yaml
server:
  name: "Build"
format:
  from-minecraft: "[%server%] %player%: %message%"
```

```
[Losos] Steve: hi there
[Build] Alex: testing the roof
```

An empty name removes the placeholder's text entirely — that is the single-server default.

## `bot`

```yaml
bot:
  token: "123456:AA..."
  chats:
    - 123456789            # a private chat with the bot
    - -1001234567890       # a group the bot is a member of
    - "@my_channel"        # a public channel where the bot is an admin
  listen: []               # empty = same as chats
```

| Key | Meaning |
|---|---|
| `token` | Token from [@BotFather](https://t.me/BotFather). Required — without it the plugin disables itself and writes a message to the console. |
| `chats` | Where Minecraft events are posted. One entry per destination, any mix of private chats, groups and channels. |
| `listen` | Which chats are read for Telegram → Minecraft. Empty means "the same as `chats`". Point it at a group id and the sender is taken from the message author, so group messages keep real names. |

**One bot, one consumer.** Telegram allows a single `getUpdates` reader per bot. If another plugin (for example TelegramChat) polls the same token, both will misbehave. Several *servers* on one bot are handled by `relay`, below.

---

## `relay` — several servers, one bot

```yaml
relay:
  mode: standalone          # standalone | hub | spoke
  token: ""                 # shared secret, the same on every server
  bind: "127.0.0.1:8765"    # where this server listens for the other side
  spokes:                   # hub only: every server that reports here
    - "http://127.0.0.1:8766"
  hub: "http://127.0.0.1:8765"   # spoke only: where the hub listens
```

Two Minecraft servers cannot both poll the same bot: `getUpdates` has exactly one consumer, so they would steal messages from each other — one server's chat appears, the other's silently disappears into whichever poller grabbed the update first. Instead, one server owns the bot and the rest talk to it over HTTP:

```
                    ┌──────────────┐
   Telegram  ◄─────►│ hub: Losos   │  owns the token, polls, posts
                    └──────┬───────┘
                           │ 127.0.0.1:8765 ⇄ 127.0.0.1:8766
                    ┌──────┴───────┐
                    │ spoke: Build │  no token, no polling
                    └──────────────┘
```

| Mode | What it does |
|---|---|
| `standalone` | Default. Own bot, own polling. One server, nothing to configure. |
| `hub` | Owns the bot: posts its own chat, accepts lines from every spoke, and sends each Telegram message back to all of them. Needs `bot.token`, `bot.chats` and `relay.spokes`. |
| `spoke` | No bot of its own. Sends its chat to `relay.hub` and prints what the hub pushes back. Needs only `relay.hub`, `relay.token` and its own `relay.bind`; `bot.token` and `bot.chats` are unused and may be empty. |

| Key | Meaning |
|---|---|
| `mode` | `standalone`, `hub` or `spoke`. |
| `token` | Shared secret, at least a dozen random characters, identical on the hub and on every spoke. A request with a different token is answered `401` and never reaches the chat. Required in `hub` and `spoke` modes — the plugin refuses to start without it. |
| `bind` | Where the listener binds. `127.0.0.1:8765` on the hub, its own port on each spoke. Keep it on loopback when the servers are on one machine; if they are not, the address has to be reachable from the hub — and the secret stops being optional politeness. |
| `spokes` | Hub only: the list of spoke addresses. A hub with an empty list still works, it just has nobody to talk to. |
| `hub` | Spoke only: the hub address. |

Behaviour worth knowing:

* **Prefixes come from `server.name`** — every server formats its own lines, including its name, before they leave it. The hub is a pipe for those lines and does not add anything of its own.
* **Muted messages stay moderated.** A player who ran `/tgrelay off` on a spoke is routed by the hub, using the hub's `moderation.chat` and its `ops.json` links — the spoke does not need a copy of that configuration.
* **Nothing is lost silently.** If the hub is unreachable, a spoke retries three times and then writes the line to its own console log. If a spoke is down, the hub logs it once every five minutes instead of flooding the log.
* **Reload limits.** `server.name`, `relay.*` and `bot.token` are read once, when the bridge starts: changing them needs a server restart, and `/tgrelay reload` says so in the console. Everything else reloads live.

---

## `format`

```yaml
format:
  from-minecraft: "%player%: %message%"
  to-minecraft: "[TG] %sender%: %message%"
```

| Placeholder | In `from-minecraft` | In `to-minecraft` |
|---|---|---|
| `%player%` | player name | — |
| `%message%` | chat text | message text |
| `%sender%` | — | Telegram display name (or `@username`) |
| `%server%` | `server.name` | `server.name` |

`%server%` also works in `events.*-format` and in `mute.prefix`. With an empty `server.name` it expands to nothing, so a single-server setup never sees a stray empty bracket.

Messages going into the game are printed with `tellraw`, so colours in the template work (`&a`, `&7`, …) and nothing is attributed to a player.

---

## `events`

```yaml
events:
  chat: true
  join: true
  quit: true
  death: true
  advancement: true
  broadcast: true

  join-format: "%player% joined the game"
  quit-format: "%player% left the game"
  death-format: "%message%"
  advancement-format: "%player% has made the advancement %advancement%"
```

Each event can be switched off individually. `death-format` receives the vanilla death message in `%message%`; `advancement-format` receives the advancement key in `%advancement%`.

Recipe advancements are skipped: they are not achievements, just noise from crafting.

---

## `polling`

```yaml
polling:
  timeout-seconds: 25
```

`getUpdates?timeout=` in seconds, clamped to 5–50. This is what makes the relay cheap: one request stays open for 25 seconds instead of a new request every second. **Do not set it to 0** — that is what turns a relay into a polling hammer and leaks sockets.

---

## `queue`

```yaml
queue:
  messages-per-second: 3
```

Outbound rate limit (clamped to 1–20). Telegram tolerates about 30 messages per second per bot; a busy server can easily exceed that during a chat flood, and throttling the bot gets you a ban. The queue holds up to 500 messages; anything beyond that is dropped with a console warning.

| `broadcast` | Server messages: `/say`, announcements from other plugins, anything that goes through the server broadcast. `say Сервер перезапустится через минуту` lands in the feed as `[Losos] Сервер перезапустится через минуту`. |

---

## `notices` — technical messages for admins

```yaml
notices:
  enabled: true
  prefix: "[TgRelay] "
  poll-failures: 5
```

Things that matter to whoever runs the server but would be noise for players:

| When | Message |
|---|---|
| the plugin starts | `сервер Losos запущен` |
| the server stops | `сервер Losos останавливается` |
| Telegram stopped answering | `Telegram не отвечает (5 ошибок подряд)`, then `связь восстановлена` |

They go where muted messages go: `moderation.chat` when it is set, otherwise a DM to every
operator that linked an account, otherwise the server console. Nothing is broadcast to players.

---

## `delivery`

```yaml
delivery:
  when-empty: skip
  hold-max-minutes: 10
  hold-max-messages: 20
```

What happens to a Telegram message when nobody is online. A player who writes to the bot
at 3 a.m. should not be left guessing whether anyone read it.

Two separate things happen to every Telegram message, and only the second one is configurable:

1. **It is always copied into `bot.chats`** (every chat except the one it came from). That is the
   point of the bridge: someone who is not in game still sees the conversation in Telegram.
2. **The in-game copy** follows `delivery.when-empty` when nobody is online:

| `when-empty` | In-game copy |
|---|---|
| `skip` (default) | nothing waits — there is nobody to show it to, and the feed already has the message |
| `wait` | the line is buffered and printed when the first player joins |

`hold`, `echo` and `drop` are the old names and still parse: `hold` → `wait`,
`echo` and `drop` → `skip`.

If a player *is* online, the message is printed in game as usual, and the feed copy is still made.

A **spoke** never buffers what the hub sends it: that copy is already handled, and the spoke
does not know whether the hub showed it. If nobody is online there, the line is logged as
dropped instead of being replayed to whoever joins next.

| Key | Meaning |
|---|---|
| `when-empty` | `skip` or `wait` — see above (`hold`, `echo`, `drop` still parse as aliases). |
| `hold-max-minutes` | How long a held message stays valid. |
| `hold-max-messages` | How many held messages are kept at most (oldest are dropped first). |

On a spoke server the echo is the hub's job: the spoke does not know which chat the message
came from, so it only prints what the hub pushes.

The hub also asks every spoke how many players it has (`POST /status`), so "nobody in game"
means nobody on **any** server: if a player is on the second server, the message is shown there
and the bot stays quiet. `delivery.hold-when-empty` is the old
boolean form and still works (`true` → `wait`, `false` → `skip`), but `when-empty` wins when both are set.

---

## `mute` — players hiding their own messages

```yaml
mute:
  enabled: true
  scope: all
  prefix: "[mute] "
```

| Key | Meaning |
|---|---|
| `enabled` | `false` — `/tgrelay off` is refused, nobody can mute themselves. |
| `scope` | `all` — mute chat and player events (join, quit, death, advancement); `chat` — mute only chat, events still go to the public chats. |
| `prefix` | Text added to muted messages on their way to the moderation channel. |

A muted player's messages are **not lost** — they are forwarded to the moderation channel (see below) with that prefix. Nothing changes in the game itself: everyone in the game still sees the chat, the mute only affects the Telegram side.

Mutes are stored in `plugins/TgRelay/muted.txt`, one UUID per line — survive restarts and name changes.

---

## `moderation` — where muted messages go

```yaml
moderation:
  chat: ""     # e.g. -1001234567890
```

Three destinations, in order of priority:

1. **`moderation.chat`** — a separate Telegram chat the players are not in. The simplest setup: add the bot to a chat, paste its id here, done. No account linking at all.
2. **A DM to every operator with a linked account.** Operators come from the server's `ops.json` — there is no second admin list to maintain, and losing op stops the DMs automatically.
3. **The server console**, so nothing disappears silently.

### Linking a Telegram account

Telegram knows user ids while Minecraft knows nicknames, and nothing connects the two — a stranger could otherwise claim somebody else's account by typing a nickname. So linking is a one-time code:

1. In game: `/tgrelay link` → the plugin prints a six-character code, valid for 10 minutes.
2. Send that code to the bot in a private message.
3. Done: the operator now receives muted messages in a DM.

`/tgrelay unlink` removes the pair. Links are stored in `plugins/TgRelay/linked.txt` as `<telegram id> <uuid>`.

On first start the plugin imports `linkedChats` from an old `plugins/TelegramChat/data.json` — **operators only**, and only if `linked.txt` is empty.

---

## State files

| File | Contents |
|---|---|
| `plugins/TgRelay/state.json` | `{"offset": …}` — the update offset. It is what stops a restart from replaying old Telegram messages into the game. Delete it to start over (the backlog will be replayed). |
| `plugins/TgRelay/muted.txt` | UUIDs of players who muted themselves. |
| `plugins/TgRelay/linked.txt` | `<telegram id> <uuid>` pairs. |
| `plugins/TgRelay/chat-names.txt` | `<chat id> <name>` — human names for logs and `/tgrelay chats`, refreshed from Telegram on start. Nothing in the relay depends on it. |
| `plugins/TgRelay/disabled-chats.txt` | Chats the plugin stopped writing to because Telegram said the bot is blocked or the chat is gone. They stay out of `bot.chats` across restarts; delete a line and run `/tgrelay reload` to try again. |

All of them are plain text — safe to edit by hand; the two chat lists are re-read by `/tgrelay reload`.

---

## Commands and permissions

| Command | Who | What |
|---|---|---|
| `/tgrelay off` / `on` | players | hide or restore your own messages |
| `/tgrelay link` / `unlink` | players | pair or unpair a Telegram account |
| `/tgrelay status` | everyone | players see their own state, admins also see counters and the moderation route |
| `/tgrelay reload` | `tgrelay.admin` | re-read `config.yml` |
| `/tgrelay testmute` | `tgrelay.admin` | send a test message through the moderation route |
| `/tgrelay chats` | `tgrelay.admin` | list every chat: live ones and the ones Telegram threw out, with names instead of bare ids |

`tgrelay.admin` defaults to op.

---

## Recipes

**One group, nothing else**

```yaml
bot:
  token: "123456:AA..."
  chats: [-1001234567890]
```

**A group plus a separate moderation chat**

```yaml
bot:
  token: "123456:AA..."
  chats: [-1001234567890]
moderation:
  chat: -1009876543210
```

**Telegram to Minecraft only, from one DM**

```yaml
bot:
  token: "123456:AA..."
  chats: []            # nothing to post to — the plugin refuses to start with an empty list
```

Note: `chats` cannot be empty; if you only need the moderation DMs, put the admin chat id there.

**Two servers, one Telegram chat**

On the server that owns the bot (`Losos`, port 25565):

```yaml
server:
  name: "Losos"
relay:
  mode: hub
  token: "a-long-random-string"
  bind: "127.0.0.1:8765"
  spokes:
    - "http://127.0.0.1:8766"
format:
  from-minecraft: "[%server%] %player%: %message%"
  join-format: "[%server%] %player% joined the game"
```

On the second server (`Build`, port 25566):

```yaml
server:
  name: "Build"
relay:
  mode: spoke
  token: "a-long-random-string"        # exactly the same secret
  bind: "127.0.0.1:8766"
  hub: "http://127.0.0.1:8765"
format:
  from-minecraft: "[%server%] %player%: %message%"
  join-format: "[%server%] %player% joined the game"
```

Both servers restart once. From then on a Telegram message lands in both game chats, and a line typed on either server reaches the same Telegram chat with its server name in front. `/tgrelay status` on each side shows the mode, the name and the peer address.
