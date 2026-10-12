# Message Triggers

A Fabric client mod for Minecraft 1.21.1 that bridges in-game chat to a
localhost HTTP agent bridge.

- Every chat line shaped `<player> message` is POSTed to `POST /api/chat`
  (fire-and-forget for untagged lines — history ingestion).
- Lines starting with the configured trigger phrase show a thinking ack
  immediately, then echo the middleware's reply verbatim (200 answer, 429
  busy, 504 timeout, 502 failure all carry a human-readable reply).
- With `publicReplies` enabled the reply is sent to the server as the player
  (everyone in chat sees it); otherwise it only echoes locally.
- Feedback-loop guard: the server echo of a reply the mod sent is never
  treated as a trigger, even if it starts with the trigger phrase.

## In-game configuration

Every tunable can be changed in-game via `/mtriggers`:

```
/mtriggers show                     list every tunable with its current value
/mtriggers set <key> <value>        change one tunable (persisted to disk)
/mtriggers reset                    restore the bundled defaults
/mtriggers path                     show where bridge.json lives
```

Keys: `middlewareBase`, `triggerPhrase`, `thinkingAck`, `failureMessage`,
`publicReplies`, `sentMemory`.

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

## E2E test

`e2e/run-e2e.sh` spawns a real Minecraft 1.21.1 client with only this mod
loaded, joins a local vanilla server, injects scripted chat lines through
the real `ChatHud` injection point, and asserts the bridge POSTs them to a
stub middleware and echoes the replies into the HUD. See `e2e/README.md`.
First run downloads the vanilla server jar and the Minecraft client
(~650 MB total, cached); later runs take ~2 minutes.
