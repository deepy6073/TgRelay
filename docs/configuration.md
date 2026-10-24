# Configuration

Everything lives in `plugins/TgRelay/config.yml`. After editing, run `/tgrelay reload` — no restart needed.

The file is created on first start from the defaults baked into the jar. Unknown keys are ignored; missing keys fall back to the defaults listed below.

---

## `language`

```yaml
language: en
```

Interface language for everything the plugin prints: `en`, `ru`, `zh`. Bundled in the jar; override a single locale by dropping your own `plugins/TgRelay/lang/<code>.yml` next to the config.

---

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

**One bot, one consumer.** Telegram allows a single `getUpdates` reader per bot. If another plugin (for example TelegramChat) polls the same token, both will misbehave.

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

---

## `delivery`

```yaml
delivery:
  hold-when-empty: true
  hold-max-minutes: 10
  hold-max-messages: 20
```

What to do with Telegram → Minecraft messages when nobody is online.

| Key | Meaning |
|---|---|
| `hold-when-empty` | `true` — keep the message and print it when the first player joins; `false` — drop it right away. |
| `hold-max-minutes` | How long a held message stays valid. |
| `hold-max-messages` | How many held messages are kept at most (oldest are dropped first). |

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

All three are plain text — safe to edit by hand while the server is stopped.

---

## Commands and permissions

| Command | Who | What |
|---|---|---|
| `/tgrelay off` / `on` | players | hide or restore your own messages |
| `/tgrelay link` / `unlink` | players | pair or unpair a Telegram account |
| `/tgrelay status` | everyone | players see their own state, admins also see counters and the moderation route |
| `/tgrelay reload` | `tgrelay.admin` | re-read `config.yml` |
| `/tgrelay testmute` | `tgrelay.admin` | send a test message through the moderation route |

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
