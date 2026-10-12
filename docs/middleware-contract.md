# Message Triggers — middleware contract (for middleware / agent developers)

This is everything a middleware (the HTTP side) developer needs to know to
talk to the Message Triggers mod. No other endpoints exist; the mod is a
pure HTTP client — it POSTs and never listens.

## 0. Deployment model: one client, one user, no server install

Message Triggers is a **client-side Fabric mod** for the player's own
client — for-the-user, by-the-user. There is **no server-side component**:

- Servers you play on need nothing installed and see nothing — the only
  server-visible side effect is that, when `publicReplies` is on, the mod
  sends the agent's replies to the server **as you**, like any chat message
  you'd type (nobody can tell a mod is involved).
- It runs in exactly one session: the chat stream you receive is the stream
  of lines rendered in *your* client's HUD — your own outgoing messages
  (as echoed by the server), other players' messages, whatever appears in
  your chat. From the middleware's point of view there is one producer and
  it stops producing when your client closes. If several clients of yours
  (or other players' clients, if they install it themselves) point at the
  same `middlewareBase`, their streams interleave with no separation — give
  each install its own endpoint if that matters to you.
- Privacy-wise, everything a middleware receives is chat **you personally
  saw**, including other people talking to each other. Host it locally and
  treat that history as yours, not a public service.

```
Your Minecraft client (mod installed, nothing server-side)
        │  POST /api/chat      every chat line YOUR client renders
        ▼
Middleware (your side)  →  consumes, processes, answers
        │  response body      JSON {"reply": "..."} when it was a trigger
        ▼
Mod echoes reply into YOUR chat HUD (or sends it to the server as YOU)
```

## 1. Endpoint: `POST {middlewareBase}/api/chat`

- `middlewareBase` is user-configured (default `http://127.0.0.1:3000`,
  typically a localhost service). The mod appends the literal path
  `/api/chat` — it is not configurable.
- Headers: `Content-Type: application/json`. No auth headers — trust model
  is "localhost loopback"; design your middleware deployment accordingly
  (bind to `127.0.0.1`, don't expose it to the LAN).

### Request payload

```json
{"sender": "E2ETester", "message": "@bot how do I get to the nether?"}
```

| Field     | Type   | Notes |
|-----------|--------|-------|
| `sender`  | string | Whoever typed the line: the mod owner, another player in the session, or the echo of a reply the mod sent (see §3). Do **not** assume `sender` is the client's user |
| `message` | string | The message text without the `<sender> ` prefix. May contain newlines (multi-line chat is captured as one line with `\n`) and any UTF-8 the game renders |

The mod captures every line **the configured client** renders as player
chat — vanilla renders a player chat line as `<sender> message`; system
messages, join/leave notices, etc. are ignored. One capture = exactly one
POST. So the stream naturally includes other players' chatter (that's the
history part of the design: the agent sees the whole conversation the user
sees), while *decisions* — what counts as a trigger — are made only on what
the user addresses to it.

### Two categories of message (decided client-side, not by you)

- **Untagged** — `message` does not start with the trigger phrase (default:
  `@bot`, configurable). The mod sends it **fire-and-forget** for history
  ingestion and *ignores your response body entirely*. Still return a
  well-formed response (a fast `202` is fine) and don't block; unresponsive
  POST handling would stall the reply path too, since both share one HTTP
  client.
- **Tagged** — `message` starts with the trigger phrase. The mod shows the
  thinking ack immediately, then **waits for your response** (async; the
  game never freezes, but the player is looking at the HUD, so aim for a
  reply within a few seconds at most).

## 2. Response (tagged messages): `{"reply": "..."}`

The mod reads **one field, `reply`, from a JSON body** and displays it
verbatim. The status code is *informational only* — the mod's behavior is
identical for 2xx and 4xx/5xx; what matters is the `reply` text in the body.

Signaling convention (inherited from the original contract; the mod treats
all four the same, but humans reading your API should too):

| Status | Meaning       | Example body |
|--------|---------------|--------------|
| `200`  | answer        | `{"reply": "E2E-REPLY-OK"}` |
| `429`  | busy / back-pressure | `{"reply": "The agent is busy, try again shortly."}` |
| `504`  | upstream timeout     | `{"reply": "The agent did not respond in time."}` |
| `502`  | middleware failure   | `{"reply": "The agent is not available."}` |

### What if you fail to respond *properly*?

- Network-level error, or body that is not JSON / lacks a usable `reply`
  string → the mod shows its configured failure message
  (`"Message Triggers failed to answer."` by default). The player never sees
  your diagnostics, so put them in your logs, not in a non-JSON body.

### Reply display rules

- `publicReplies=false` (default in most setups): the reply is echoed to the
  local chat HUD only — nobody else in the game sees it.
- `publicReplies=true`: the reply is sent to the Minecraft server **as the
  player**, so everyone in chat sees it. Consequences for you:
  - Split your reply into lines yourself (`\n`); each line becomes one chat
    message. Each line **must be ≤ 256 chars** (Minecraft's per-message chat
    limit) — enforce this centrally in your response layer.
  - The mod re-POSTs those lines when the server echoes them back (their
    text re-enters the history stream, see §3). Keep replies free of
    character sequences that look like they were written by the agent:
    they'll be indistinguishable from real chat in your history.
- The thinking ack is always local-only; never expect to see it server-side.

## 3. Feedback loop (why you'll see your own replies as POSTs)

When a reply is displayed, the server echoes it back to the client as normal
chat (`<player> your reply text`), and the mod POSTs that echo like any other
chat line — replies therefore land in your history stream. Two guarantees
you can rely on:

1. **Never treated as triggers**: the mod remembers the lines it sent
   (rolling buffer, `sentMemory`, default 16) and an echo of one of them can
   never trigger — even if the reply text happens to start with the trigger
   phrase. The bot cannot accidentally talk to itself.
2. **History only**: an echo POST is untagged-category behavior (no reply
   requested) — so `{"reply": …}` handling on such a line is wasted work.

## 4. What the mod never does

- No batching: one HTTP request per chat line, in arrival order (responses
  are async, so untagged POSTs may out-of-order *arrive* if you log by
  completion time — use the upstream order or a per-line timestamp you
  generate).
- No retries: a failed request is logged and (for tagged messages) surfaces
  the cached failure message. Idempotency is on you if you retry.
- No inbound traffic: the mod opens no server socket, exposes no health
  route, and polls nothing. It only speaks on chat arrival and then goes
  silent while your answer travels back the HTTP response path — there are
  no heartbeats; gauge liveness from activity (or a client closing its
  session means no future traffic).
- No game-control surface: the mod never sends commands, moves the player,
  or reads inventory/position — its only effect on the game is putting text
  into (or out of) the chat. If your agent needs to act inside the game,
  that is a different, server-side integration; this mod is intentionally
  not it.

## 5. Tunables that affect your side (`/mtriggers`, persisted to `bridge.json`)

| Key              | Default                              | Effect on you |
|------------------|--------------------------------------|---------------|
| `middlewareBase` | `http://127.0.0.1:3000`              | Base URL you will be posted to |
| `triggerPhrase`  | `@bot`                               | The message prefix that turns fire-and-forget into a blocking reply request |
| `thinkingAck`    | `...thinking...`                     | Local-only ack text (invisible to you) |
| `failureMessage` | `Message Triggers failed to answer.` | What the player sees when your service errors or returns a bad body |
| `publicReplies`  | `true`                               | Whether your replies go to the server (see §2) |
| `sentMemory`     | `16`                                 | Size of the echo-guard buffer (see §3) |
| `wsEnabled`      | `false`                              | Whether the mod connects the push channel (§6) |

## 6. Push channel (optional): `GET {middlewareBase}/api/ws` — WebSocket

The POST/response loop above ties every inbound game message to a reply the
HTTP side returns. For anything **unprompted** (proactive agent messages,
scheduled reminders, webhooks invocations from other systems), the mod can
maintain a **WebSocket push channel** — same origin as `middlewareBase`
(`http→ws`, `https→wss`), path `/api/ws`.

### Protocol rules

- The mod connects and **listens only** — it sends nothing in v1, and it
  reconnects **indefinitely with backoff** on disconnects/errors, so it is
  safe to serve nothing to it.
- Frames: one JSON object per **text frame**:

  ```json
  {"text": "the agent needs your attention", "mode": "local"}
  ```

  | Field  | Type   | Notes                                                                      |
  |--------|--------|----------------------------------------------------------------------------|
  | `text` | string | The message to display; blank text frames are silently dropped             |
  | `mode` | string | `local` (default) → shown only to the player; `public` → posted to the server as the player. **Anything else drops the frame** — the mode names are exact and case-sensitive |

  Unknown JSON fields are ignored for forward compatibility.
- The mod **must not be trusted with reliability**: the transport is
  fire-and-forget from your side. If your middleware restarts or the player
  alt-tabs through a network flap, queue-and-discipline your messages
  there; the reconnecting client means you do NOT need to gate app
  behavior on push delivery.
- publicReplies applies: a `public` push still lands locally if the player
  disabled public replies, and it obeys the 256-char-per-line rule (§2).
- The reach of "pushed" texts is the game-limit: the server will re-echo
  them back to you as ordinary chat POSTs (they enter your history).

### Enabling

`/mtriggers set wsEnabled true` (or `"wsEnabled": true` in bridge.json).
Default is `false` — middleware implementations should treat the socket as
additive, never as the only way to reach the player: a chat-triggered
`{"reply": ...}` response is still the guaranteed path today (§2).

## 7. Testing your middleware against the mod

- `e2e/stub-middleware.py` is a ~60-line reference implementation of this
  contract (untagged → `202`, tagged → `200`, tagged + `failme` → `502`;
  every request appended to a JSONL log). Copy it as the scaffold for a real
  middleware or as a mock for interactive testing.
- `e2e/run-e2e.sh` boots a real Minecraft client against the vanilla server
  and drives chat through it — the doc for each check is `e2e/README.md`.
- Fastest interactive loop: start the stub locally, point a Minecraft
  instance at it (`/mtriggers set middlewareBase http://127.0.0.1:32100`),
  and chat.

## 8. Minimal compatibility checklist

A middleware is contract-compatible when it:

- [ ] accepts `POST /api/chat` with `Content-Type: application/json`
      payload `{"sender": str, "message": str}`
- [ ] returns `Content-Type: application/json` body `{"reply": str}` for
      trigger messages (any status code; use 200/429/504/502 by convention)
- [ ] returns *some* well-formed response for untagged messages (a fast
      `202` is idiomatic) — the body's shape is irrelevant client-side
- [ ] never causes the connection to hang longer than the player would
      tolerate (mod-side timeout does not exist; nothing but the HTTP stack
      will time you out)
- [ ] keeps every reply line ≤ 256 chars if `publicReplies` can be on

And optionally, for the push channel (§6):

- [ ] serves `GET /api/ws` as a WebSocket endpoint when it wants to push
      unprompted; serves nothing when it doesn't (the mod reconnects
      forever, so a refused connection is a supported steady state)
- [ ] pushes only `{"text": str, "mode": "local"|"public"}` frames and
      tolerates the client never sending anything back
