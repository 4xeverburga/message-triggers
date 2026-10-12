# message-triggers E2E harness

Spawns a **real Minecraft client** (Fabric dev env via Loom's
`runE2eClient` run config) with only the message-triggers mod loaded, joins a local
vanilla server, and drives a scripted chat scenario through the real
`ChatHud` injection point — the same mixin hook production uses.

## Version branches

The harness is version-agnostic: the Minecraft version comes from
`minecraft_version` in `gradle.properties`, and the vanilla server jar is
fetched for that version on demand. Testing on another Minecraft version
means only:

1. Branch per version (pattern: `1.21.1`, `1.20.x`, ...) off the closest
   existing branch.
2. Bump the fabric toolchain block in `gradle.properties`
   (`minecraft_version`, `yarn_mappings`, `loader_version`, `fabric_version`);
   when the mod code needs version-specific changes, make them on the
   version branch. Versions live at https://fabricmc.net/develop and
   https://fabricmc.net/use.
3. If the harness breaks across versions (API moves in `ChatHud`,
   quickPlay args, etc.), fix it on that branch — the driver mod is plain
   Fabric/mixin code and may need adjusting for older targets.

Scope: **message-triggers only**. The middleware is a deterministic stub
(`stub-middleware.py`, port 32100) implementing the `/api/chat` contract;
the MemoChat mod is NOT loaded.

## Run it

```sh
e2e/run-e2e.sh
```

First run downloads the vanilla server jar (~55 MB, cached in `e2e/.cache/`)
and the Minecraft client + assets via Loom (~600 MB, cached in the Gradle
caches). Later runs take ~2 minutes.

## What it asserts

| Check | Meaning |
|---|---|
| `bridge loaded` in client log | the mod booted with the harness config |
| thinking ack in HUD | tagged line shows `...thinking...` immediately |
| agent reply in HUD | stub's 200 reply is echoed into chat |
| failure reply in HUD | stub's 502 reply is echoed into chat |
| untagged line reached middleware | fire-and-forget POST happened |
| tagged / failure lines reached middleware | payloads carry sender + message |

The driver mod (`e2edriver`) records every HUD line via a mixin on the same
`ChatHud#addMessage` overload the production `ChatHudMixin` hooks, injects
the scripted lines, polls for the expected echoes, writes the verdict to
`e2e/run/e2e-result.json`, and stops the client.

## Pieces

- `../src/e2e/` — driver mod as a second source set of the root project
  (loom run config `e2eClient` in the root `build.gradle`; a loom subproject
  depending on this project's jar fails at configuration time, so it lives here)
  - `java/.../E2EDriverMod.java` — scenario state machine + assertions
  - `java/.../mixin/ChatRecorderMixin.java` — HUD line recorder
  - `resources/fabric.mod.json`, `resources/e2edriver.mixins.json`
- `stub-middleware.py` — `/api/chat` stub + request log
- `run-e2e.sh` — orchestration (configs, server, client, verdict)
- `../.github/workflows/e2e.yml` — manual CI trigger (`workflow_dispatch`,
  xvfb; Minecraft-in-CI is flaky, so it never gates pushes)
