# Message Triggers — middleware contract v4 (for middleware / agent developers)

This is everything a middleware developer needs to know to talk to the
Message Triggers mod. ONE surface exists, both directions on the same
WebSocket on your `middlewareBase` origin:

- `GET /api/ws` — the single bidirectional channel. Chat lines travel
  client→server as `{"type": "chat", ...}` frames; answers, notices and
  proactive pings travel server→client as `{"text", "mode"}` frames.
- **There is no HTTP.** The mod never makes an HTTP request.

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
        │  WS {"type": "chat"} frames     every chat line YOUR client renders
        ▼
Middleware (your side)  →  consumes, processes, decides what to say
        │  WS {"text", "mode"} frames    answers/pings, any time
        ▼
Mod shows it: local echo, or sent to the server AS YOU
```

## 1. The single channel: `GET {middlewareBase}/api/ws`

- `middlewareBase` is user-configured (default `http://127.0.0.1:3000`,
  typically a localhost service; `http→ws`, `https→wss`). The mod appends
  the literal path `/api/ws` — it is not configurable.
- No auth headers — trust model is "localhost loopback"; design your
  middleware deployment accordingly (bind to `127.0.0.1`, don't expose it
  to the LAN).
- The mod connects at boot and reconnects **indefinitely with exponential
  backoff (1s → 30s)**, so serving nothing to it is a supported steady
  state — but a middleware that wants *anything* answered or forwarded
  must serve it.
- One frame, one unit of work, both directions below.

### Client → server: chat frames

```json
{"type": "chat", "sender": "E2ETester", "message": "how do I get to the nether?"}
```

| Field     | Type   | Notes |
|-----------|--------|-------|
| `type`    | string | Always `"chat"` |
| `sender`  | string | Whoever typed the line: the mod owner, another player in the session, or the echo of a message the mod sent (see §3). Do **not** assume `sender` is the client's user |
| `message` | string | The message text without the `<sender> ` prefix. May contain newlines (multi-line chat is captured as one line with `\n`) and any UTF-8 the game renders |

Rules:

- The mod captures every line **the configured client** renders as player
  chat — vanilla renders a player chat line as `<sender> message`; system
  messages, join/leave notices, etc. are not forwarded. Ordering is
  socket order — no reordering is possible end-to-end.
- **Fire-and-forget, no frame-level retries**: while disconnected the mod
  *drops* chat frames (transition gaps during a reconnect ladder lose
  lines). The mod is not your durable transport — the middleware's
  working set of "what chat order and coverage it needs" is yours to
  design (e.g. also ingest from other sources, or reconnect-gap
  compensation in your agent logic).
- The mod is a dumb pipe: it forwards EVERY chat line and decides
  nothing. What deserves an answer, and when, is entirely your side —
  push *something* for a question the player is waiting on (the answer,
  a busy note, a timeout note), because the player sees nothing until
  you push.

### Server → client: push/answer frames

```json
{"text": "the agent answered something", "mode": "public"}
```

| Field  | Type   | Notes                                                                      |
|--------|--------|----------------------------------------------------------------------------|
| `text` | string | The message to display; blank text frames are silently dropped             |
| `mode` | string | `local` (default) → shown only to the player; `public` → posted to the server as the player; `localStream` → streaming chunk, local only (below). **Anything else drops the frame** — the mode names are exact and case-sensitive |

Unknown JSON fields are ignored for forward compatibility.

**Streaming (`mode: "localStream"`)**: a chunk is appended to the
currently streamed local text and the whole line is re-rendered in place
— ONE HUD line per stream, no matter how many chunks. The first chunk
opens the line; a `local` or `public` frame closes the stream, and the
next `localStream` chunk starts a fresh one. Chunks are fire-and-forget
like everything else: one lost in a disconnect is simply missing from the
line — for text the player must reliably see, push the whole message as
one `local` frame instead. Streaming never sends anything to the server.

- **Correlating replies to chat lines is your job** (there is no
  request/response pairing on the socket). Use the history you ingested
  (you own both ends of the correlation).
- The mod **must not be trusted with reliability** of pushes either: the
  transport is fire-and-forget from your side. If your middleware
  restarts or the player alt-tabs through a network flap,
  queue-and-discipline your messages there; the reconnecting client means
  you do NOT need to gate app behavior on push delivery.

## 2. Reliability (both directions)

- Neither side retries. Frames that did not make it during a disconnect
  are lost, period.
- This is deliberate: the mod is a one-user, one-session toy surface for
  your agent, not a messaging system. Queue what matters (full report
  text, alerts), skip what doesn't (stream chatter). Both directions are
  symmetric fire-and-forget; if you need guarantees, treat the WS as a
  notification rail and build durability in your middleware.
- Liveness: no heartbeats at all on either channel; gauge liveness from
  frame activity or a closed session.

## 3. Feedback loop (why you'll see your own messages as chat frames)

When a pushed message is displayed, the server echoes it back to the
client as normal chat (`<player> your message text`), and the mod forwards
that echo like any other chat frame — replies therefore land in your
history stream. One guarantee you can rely on:

1. **Just history**: an echo frame is not an expectation of an answer —
   pushing a reply to every echo you see is wasted work (and a loop).
   The mod does nothing special with echoes; filter them in your
   middleware — you know what you pushed.

## 4. What the mod never does

- No HTTP: no POST, no health route, no polling, no server socket. WS
  only.
- No batching: one chat frame per chat line, in arrival order.
- No retries on either channel (§2).
- No game-control surface: the mod never sends commands, moves the
  player, or reads inventory/position — its only effects on the game are
  chat text in (push) and chat text out (frame forwarding). If your agent
  needs to act inside the game, that is a different, server-side
  integration; this mod is intentionally not it.
- No correlation protocol: the socket carries plain display/chat frames.
  If you need request/response pairing, correlate in your middleware (you
  own both the chat history and the push).

## 5. Tunables that affect your side (`/mtriggers`, persisted to `bridge.json`)

| Key             | Default                              | Effect on you |
|-----------------|--------------------------------------|---------------|
| `middlewareBase`| `http://127.0.0.1:3000`              | Origin you will be connected to (frames both ways) |
| `publicReplies` | `true`                               | Whether pushed public messages go to the server (see §1 push frames) |

## 6. Testing your middleware against the mod

- `e2e/stub-middleware.py` is a ~90-line reference implementation of this
  contract (one WS endpoint: logs inbound chat frames to JSONL, queues a
  reply for a tagged line, pushes it as a `public` frame, and streams a
  local message as `localStream` chunks). Copy it as the scaffold for a
  real middleware or as a mock for interactive testing.
- `e2e/run-e2e.sh` boots a real Minecraft client against the vanilla
  server and drives chat through it — the doc for each check is
  `e2e/README.md`.
- Fastest interactive loop: start the stub locally, point a Minecraft
  instance at it (`/mtriggers set middlewareBase http://127.0.0.1:32100`),
  and chat.

## 7. Minimal compatibility checklist

A middleware is contract-compatible when it:

- [ ] serves `GET /api/ws` as a WebSocket endpoint (the ONLY endpoint the
      mod touches; a connection refused is tolerated forever by the mod,
      but nothing is forwarded or answered then)
- [ ] parses inbound frames `{"type": "chat", "sender": str, "message": str}`
      (unknown client frame shapes may appear in the future — ignore what
      has no `type: "chat"`)
- [ ] pushes only `{"text": str, "mode": "local"|"public"|"localStream"}`
      frames and tolerates the client sending nothing back for them
- [ ] pushes **some** message for every question the player is waiting on
      (the player sees nothing until you do)
- [ ] keeps every pushed line ≤ 256 chars if `publicReplies` can be on
