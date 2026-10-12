# Message Triggers — middleware contract (for middleware / agent developers)

This is everything a middleware (the HTTP side) developer needs to know to
talk to the Message Triggers mod. No other endpoints exist; the mod is a
pure HTTP client — it POSTs and never listens.

```
Minecraft client (mod installed)
        │  POST /api/chat      every parsed chat line (see below)
        ▼
Middleware (your side)  →  consumes, processes, answers
        │  response body      JSON {"reply": "..."} when it was a trigger
        ▼
Mod echoes reply into the chat HUD (or sends it to the server as you)
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
| `sender`  | string | The player name as captured from the chat line (`<sender>` portion) |
| `message` | string | The message text without the `<sender> ` prefix. May contain newlines (multi-line chat is captured as one line with `\n`) and any UTF-8 the game renders |

The mod captures **player chat only**: vanilla renders a player chat line as
`<sender> message`; system messages, join/leave notices, etc. are ignored.
One incoming line = exactly one POST.

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
- No inbound traffic: the mod opens no server socket, has no health route,
  and polls nothing. It only speaks when spoken to (chat), and it does not
  send periodic heartbeats — consider logging per-sender activity to track
  liveness instead.
- No ME/player-visible state mutation beyond chat: the mod's other surface
  is out of scope — it never sends commands or controls movement.

## 5. Tunables that affect your side (`/mtriggers`, persisted to `bridge.json`)

| Key              | Default                              | Effect on you |
|------------------|--------------------------------------|---------------|
| `middlewareBase` | `http://127.0.0.1:3000`              | Base URL you will be posted to |
| `triggerPhrase`  | `@bot`                               | The message prefix that turns fire-and-forget into a blocking reply request |
| `thinkingAck`    | `...thinking...`                     | Local-only ack text (invisible to you) |
| `failureMessage` | `Message Triggers failed to answer.` | What the player sees when your service errors or returns a bad body |
| `publicReplies`  | `true`                               | Whether your replies go to the server (see §2) |
| `sentMemory`     | `16`                                 | Size of the echo-guard buffer (see §3) |

## 6. Testing your middleware against the mod

- `e2e/stub-middleware.py` is a ~60-line reference implementation of this
  contract (untagged → `202`, tagged → `200`, tagged + `failme` → `502`;
  every request appended to a JSONL log). Copy it as the scaffold for a real
  middleware or as a mock for interactive testing.
- `e2e/run-e2e.sh` boots a real Minecraft client against the vanilla server
  and drives chat through it — the doc for each check is `e2e/README.md`.
- Fastest interactive loop: start the stub locally, point a Minecraft
  instance at it (`/mtriggers set middlewareBase http://127.0.0.1:32100`),
  and chat.

## 7. Minimal compatibility checklist

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
