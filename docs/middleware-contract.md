# Message Triggers — middleware contract v4

The mod is a dumb pipe over ONE WebSocket: it forwards every player-chat
line your client renders, and displays whatever you push. What to answer,
and when, is entirely your side.

## Channel

`GET {middlewareBase}/api/ws` — the only endpoint, both directions.
`middlewareBase` is user-configured (default `http://127.0.0.1:3000` — the
middleware's WebSocket port; `http→ws`, `https→wss`), no auth
(localhost-loopback trust model). The
mod connects at boot and reconnects forever with backoff (1s → 30s).

## Client → server

```json
{"type": "chat", "sender": "E2ETester", "message": "how do I get to the nether?"}
```

| Field     | Type   | Notes |
|-----------|--------|-------|
| `type`    | string | Always `"chat"` |
| `sender`  | string | Whoever typed the line — possibly you, another player, or the echo of a message you pushed. Do not assume it is the client's user |
| `message` | string | Text without the `<sender> ` prefix; may contain `\n` |

- One frame per chat line, in arrival order. Fire-and-forget: while
  disconnected, lines are dropped — no client-side queueing or retries.
- Echoes of your own pushes arrive here like any other line; filter them
  yourself (you know what you pushed).

## Server → client

```json
{"text": "the agent answered something", "mode": "public"}
```

| Field  | Type   | Notes |
|--------|--------|-------|
| `text` | string | Non-blank; blank frames are dropped |
| `mode` | string | `local` (default) \| `public` \| `localStream`. Anything else drops the frame |

- `local` — shown to the player only.
- `public` — sent to the server **as the player** (falls back to a local
  echo when `publicReplies` is off or no world is joined yet). Keep each
  line ≤ 256 chars; newlines become one message per line.
- `localStream` — a streaming chunk, local only. Consecutive chunks
  accumulate into ONE HUD line, re-rendered in place; a `local` or
  `public` frame closes the stream, the next chunk starts a fresh one.
- Unknown JSON fields are ignored (forward compatibility).
- Fire-and-forget: no delivery guarantees, no heartbeats. Queue what
  matters in your middleware.

## Tunables (`/mtriggers`, persisted to `bridge.json`)

| Key             | Default                 | Effect |
|-----------------|-------------------------|--------|
| `middlewareBase`| `http://127.0.0.1:3000` | Origin you are connected to |
| `publicReplies` | `true`                  | Whether `public` pushes go to the server |

## Compatibility checklist

- [ ] serves `GET /api/ws` (the only endpoint the mod touches)
- [ ] parses `{"type": "chat", "sender", "message"}`; ignores frames
      without `type: "chat"`
- [ ] pushes only `{"text", "mode": "local"|"public"|"localStream"}`

## Testing

- `e2e/stub-middleware.py` — ~90-line reference middleware (port 32100):
  logs chat frames to JSONL, queues a reply for a tagged line, pushes it
  as `public`, streams a local message as `localStream` chunks.
- `e2e/run-e2e.sh` boots a real Minecraft client against the vanilla
  server and drives chat through it; see `e2e/README.md`.
- Interactive: run the stub, `/mtriggers set middlewareBase http://127.0.0.1:32100`, chat.

