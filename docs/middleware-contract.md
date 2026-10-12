# Message Triggers — middleware contract v2 (for middleware / agent developers)

This is everything a middleware (the HTTP side) developer needs to know to
talk to the Message Triggers mod. Two surfaces exist, both on your
`middlewareBase` origin:

- `POST /api/chat` — one-way notification: what your (the user's) Minecraft
  client renders as chat, one POST per line. **The response body is never
  read.**
- `GET /api/ws` — WebSocket push channel: the **only** way anything (trigger
  replies, busy/timeout notices, proactive pings) reaches the player.

### v2 — breaking changes from v1

- **`{"reply": ...}` in the POST response is gone.** The mod ignores the
  response body entirely; a middleware that still answers over HTTP will
  look dead to the player. All answers are WS pushes now.
- **The status-code signaling convention (200/429/504/502 semantics) is
  retired.** What the player sees instead is whatever you push, whenever
  you push it.
- **`wsEnabled` defaults to `true`** (it used to additively opt into the
  push channel; now the push channel *is* the reply path). Only configs
  that predate the field default on — old explicit `false` still disables
  replies.

## 0. Deployment model: one client, one user, no server install

Message Triggers is a **client-side Fabric mod** for the player's own
client — for-the-user, by-the-user. There is **no server-side component**:

- Servers you play on need nothing installed and see nothing — the only
  server-visible side effect is that, when `publicReplies` is on, the mod
  sends pushed messages to the server **as you**, like any chat message
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
        │  POST /api/chat      every chat line YOUR client renders (one-way)
        ▼
Middleware (your side)  →  consumes, processes, decides what to say
        │  WS frames {"text", "mode"}   answers via /api/ws, any time
        ▼
Mod shows it: local echo, or sent to the server AS YOU
```

## 1. `POST {middlewareBase}/api/chat` — one-way chat notification

- `middlewareBase` is user-configured (default `http://127.0.0.1:3000`,
  typically a localhost service). The mod appends the literal path
  `/api/chat` — it is not configurable.
- Headers: `Content-Type: application/json`. No auth headers — trust model
  is "localhost loopback"; design your middleware deployment accordingly
  (bind to `127.0.0.1`, don't expose it to the LAN).
- **Response: return something fast (`202` with any body is idiomatic) and
  get out of the way.** The mod reads nothing from it: not the body, not
  the status code. There is no retry either — a network-level failure is
  logged and, for trigger messages, is the only failure the mod can detect
  (see §2).

### Request payload

```json
{"sender": "E2ETester", "message": "@bot how do I get to the nether?"}
```

| Field     | Type   | Notes |
|-----------|--------|-------|
| `sender`  | string | Whoever typed the line: the mod owner, another player in the session, or the echo of a message the mod sent (see §3). Do **not** assume `sender` is the client's user |
| `message` | string | The message text without the `<sender> ` prefix. May contain newlines (multi-line chat is captured as one line with `\n`) and any UTF-8 the game renders |

The mod captures every line **the configured client** renders as player
chat — vanilla renders a player chat line as `<sender> message`; system
messages, join/leave notices, etc. are ignored. One capture = exactly one
POST. So the stream naturally includes other players' chatter (that's the
history part of the design: the agent sees the whole conversation the user
sees), while *decisions* — what counts as a trigger — are made only on what
the user addresses to it.

Trigger messages (default trigger: `@bot`, configurable) are additionally
marked client-side with a local `...thinking...` ack — invisible to you,
and no timeout follows it client-side: the player then waits for you to
**push** an answer (§2). If you never push, the ack just sits there, so
always push *something* for a trigger you see (the answer, a busy note, a
timeout note).

## 2. `GET {middlewareBase}/api/ws` — WebSocket push channel (required)

This is where all answers travel. The mod connects at boot (when
`wsEnabled`, the default) and reconnects **indefinitely with exponential
backoff (1s → 30s)**, so serving nothing to it is a supported steady state —
but a middleware that wants *triggers answered* must serve it.

### Protocol rules

- The mod **listens only** — it sends nothing on this socket, ever.
- Frames: one JSON object per **text frame**:

  ```json
  {"text": "the agent answered something", "mode": "public"}
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
- **Correlating replies to triggers is your job** (the socket carries no
  request/response pairing in v2). The trigger POST arrives at most seconds
  before its answer should be pushed; use the history you ingested.

### Display rules

- `publicReplies=true` (default): a `public` frame is sent to the Minecraft
  server **as the player**, so everyone in chat sees it. Consequences:
  - Split into lines yourself (`\n`); each line becomes one chat message.
    Each line **must be ≤ 256 chars** (Minecraft's per-message chat
    limit) — enforce this centrally in your push layer.
  - The server echoes those lines back to the client, and the mod re-POSTs
    them (they re-enter the history stream, see §3). Keep pushed texts free
    of character sequences that look like they were written by the agent —
    they'll be indistinguishable from real chat in your history.
  - A `public` push that arrives before a world is joined (title screen —
    the socket connects at boot) degrades to a local echo, never a crash
    or a lost message.
- `publicReplies=false`: everything lands as a local echo (nobody else sees
  it), regardless of `mode`.
- The thinking ack is always local-only; never expect to see it server-side.

## 3. Feedback loop (why you'll see your own messages as POSTs)

When a pushed message is displayed, the server echoes it back to the client
as normal chat (`<player> your message text`), and the mod POSTs that echo
like any other chat line — replies therefore land in your history stream.
Two guarantees you can rely on:

1. **Never treated as triggers**: the mod remembers the lines it sent
   (rolling buffer, `sentMemory`, default 16) and an echo of one of them can
   never trigger — even if the text happens to start with the trigger
   phrase. The bot cannot accidentally talk to itself.
2. **History only**: an echo POST is not an expectation of an answer —
   pushing a reply to every echo you see is wasted work (and a loop).

## 4. What the mod never does

- No batching: one HTTP request per chat line, in arrival order.
- No retries: a failed POST is logged (and surfaces the configured failure
  message for triggers). Idempotency is on you if you retry.
- No polling, no server socket, no health route: the only inbound
  connection is the mod's own **outgoing** WebSocket (§2). There are no
  heartbeats on either channel; gauge liveness from activity or a closed
  session.
- No retries of pushes either: what arrives is displayed once; missing
  frames are your queue's problem.
- No game-control surface: the mod never sends commands, moves the player,
  or reads inventory/position — its only effect on the game is putting text
  into (or out of) the chat. If your agent needs to act inside the game,
  that is a different, server-side integration; this mod is intentionally
  not it.
- No correlation protocol: the WS channel carries plain display frames. If
  you need request/response pairing, correlate in your middleware (you own
  both the chat history and the push).

## 5. Tunables that affect your side (`/mtriggers`, persisted to `bridge.json`)

| Key              | Default                              | Effect on you |
|------------------|--------------------------------------|---------------|
| `middlewareBase` | `http://127.0.0.1:3000`              | Base URL you will be posted to (and the origin of the WS push channel) |
| `triggerPhrase`  | `@bot`                               | The message prefix that marks a trigger (you must still push an answer for it) |
| `thinkingAck`    | `...thinking...`                     | Local-only ack text (invisible to you) |
| `failureMessage` | `Message Triggers failed to answer.` | Shown only when the POST itself fails at the network level (the trigger never reached you) — agent slowness is YOUR job to announce |
| `publicReplies`  | `true`                               | Whether pushed public messages go to the server (see §2) |
| `sentMemory`     | `16`                                 | Size of the echo-guard buffer (see §3) |
| `wsEnabled`      | `true`                               | Whether the mod connects the push channel at all (§2). `false` = no answers ever reach the player |

## 6. Testing your middleware against the mod

- `e2e/stub-middleware.py` is a ~80-line reference implementation of this
  contract (one-way `202` for every POST; tagged triggers queue a reply
  that is pushed as a `public` frame; every request appended to a JSONL
  log). Copy it as the scaffold for a real middleware or as a mock for
  interactive testing.
- `e2e/run-e2e.sh` boots a real Minecraft client against the vanilla server
  and drives chat through it — the doc for each check is `e2e/README.md`.
- Fastest interactive loop: start the stub locally, point a Minecraft
  instance at it (`/mtriggers set middlewareBase http://127.0.0.1:32100`),
  and chat.

## 7. Minimal compatibility checklist

A middleware is contract-compatible when it:

- [ ] accepts `POST /api/chat` with `Content-Type: application/json`
      payload `{"sender": str, "message": str}` and returns *some* fast
      response (a `202` is idiomatic) whose body is never read
- [ ] serves `GET /api/ws` as a WebSocket endpoint (required for any
      useful behavior beyond history ingestion; a connection refused is
      tolerated forever by the mod, but nothing reaches the player then)
- [ ] pushes only `{"text": str, "mode": "local"|"public"}` frames and
      tolerates the client never sending anything back
- [ ] pushes **some** message for every trigger it sees (the player sees
      only the thinking ack until you do)
- [ ] keeps every pushed line ≤ 256 chars if `publicReplies` can be on
