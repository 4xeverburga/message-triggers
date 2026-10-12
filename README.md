# Message Triggers

A Fabric client mod for Minecraft 1.21.1 that bridges in-game chat to a
localhost HTTP agent bridge. Install it on **your own client** — there is
no server-side component; servers and other players need (and see) nothing.

- ONE channel, a `GET /api/ws` WebSocket to the middleware host — no HTTP
  at all. Every chat line shaped `<player> message` is forwarded over it
  as a `{"type": "chat"}` frame (fire-and-forget; dropped while
  disconnected).
- Messages the middleware pushes on the same socket are displayed: line
  above the configured trigger phrase → thinking ack immediately, then
  whatever comes back (its reply, busy/timeout notices, proactive pings
  — see the contract).
- With `publicReplies` enabled, public pushes are sent to the server as
  the player (everyone in chat sees it); otherwise everything only echoes
  locally.
- Feedback-loop guard: the server echo of a message the mod sent is never
  treated as a trigger, even if it starts with the trigger phrase.

## Middleware contract (for middleware / agent developers)

If you are writing the HTTP side that this mod talks to (or wiring up a
real agent behind it), everything you need lives in
[`docs/middleware-contract.md`](docs/middleware-contract.md): the single
bidirectional `GET /api/ws` channel (chat frames in, push frames out —
the whole contract since v3, when `POST /api/chat` was deleted),
display/feedback-loop behavior, tunables, and a minimal compatibility
checklist. `e2e/stub-middleware.py` is a runnable reference implementation.

## In-game configuration

Every tunable can be changed in-game via `/mtriggers`:

```
/mtriggers show                     list every tunable with its current value
/mtriggers set <key> <value>        change one tunable (persisted to disk)
/mtriggers reset                    restore the bundled defaults
/mtriggers path                     show where bridge.json lives
```

Keys: `middlewareBase`, `triggerPhrase`, `thinkingAck`, `publicReplies`,
`sentMemory`.

Config lives at `<minecraft config dir>/messagetriggers/bridge.json`. When the
file is missing or malformed, bundled defaults are used — the mod works out of
the box with `middlewareBase = http://127.0.0.1:3000` and
`triggerPhrase = @bot`.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. Requires JDK 25 to run Gradle (Fabric
Loom 1.18) and targets Java 21. The mod depends on Fabric API's client
command module (`fabric-command-api-v2`).

## Tested Minecraft versions

Latest tested: **1.21.1** (e2e-verified: `e2e/run-e2e.sh` against 1.21.1).
Other Minecraft versions live on per-version branches (`<version>`) and are
e2e-tested there before a merge; see `e2e/README.md` for the workflow.

## E2E test

`e2e/run-e2e.sh` spawns a real Minecraft 1.21.1 client with only this mod
loaded, joins a local vanilla server, injects scripted chat lines through
the real `ChatHud` injection point, and asserts the bridge POSTs them to a
stub middleware and echoes the replies into the HUD. See `e2e/README.md`.
First run downloads the vanilla server jar and the Minecraft client
(~650 MB total, cached); later runs take ~2 minutes.
