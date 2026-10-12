#!/usr/bin/env bash
#
# E2E harness for the message-triggers mod (Minecraft 1.21.1).
#
# Spawns a REAL Minecraft client (Fabric dev env via Loom's runClient) with
# ONLY the message-triggers mod loaded, joins a local vanilla server, injects
# scripted chat lines through the real ChatHud injection point, and asserts
# that the bridge POSTs them to a stub middleware and echoes the replies
# into the HUD. The driver mod writes the verdict to e2e/run/e2e-result.json
# and stops the client itself.
#
# Layout:
#   e2e/stub-middleware.py — deterministic /api/chat stub (port 32100)
#   e2e/run/               — Loom run dir (client configs, logs, results)
#   e2e/.cache/            — cached vanilla server jar (~55 MB, one-time)
#   e2e/.server/           — vanilla server working dir
#
# Usage: e2e/run-e2e.sh   (from anywhere; JAVA_HOME defaults to ~/.jdk/jdk-25.0.2)
set -euo pipefail

E2E_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$E2E_DIR/.." && pwd)"
RUN_DIR="$E2E_DIR/run"
CACHE_DIR="$E2E_DIR/.cache"
SERVER_DIR="$E2E_DIR/.server"

MC_VERSION="1.21.1"
STUB_PORT=32100
CLIENT_TIMEOUT_SECS=900
SERVER_TIMEOUT_SECS=180

# Loom 1.18 needs a JVM >= 25 to run Gradle. If the ambient JAVA_HOME is
# older (e.g. a Java 21 dev JDK), fall back to the local JDK 25 install.
if [ -x "${JAVA_HOME:-}/bin/java" ] && \
	"$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -qE 'version "(2[5-9]|[3-9][0-9])'; then
	: # ambient JAVA_HOME is new enough
else
	export JAVA_HOME="$HOME/.jdk/jdk-25.0.2"
fi
JAVA="$JAVA_HOME/bin/java"
if [ ! -x "$JAVA" ]; then
	echo "ERROR: JDK 25 not found at $JAVA_HOME (Loom needs JVM 25 to run Gradle)"
	exit 1
fi

log() { echo "[e2e] $*"; }

cleanup() {
	if [ -n "${SERVER_PID:-}" ]; then kill "$SERVER_PID" 2>/dev/null || true; fi
	if [ -n "${STUB_PID:-}" ]; then kill "$STUB_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT

# ── 1. run dir + bridge config (publicReplies=false → local echo only) ────
mkdir -p "$RUN_DIR/config/messagetriggers"
rm -f "$RUN_DIR/e2e-result.json" "$RUN_DIR/stub-requests.jsonl"
cat > "$RUN_DIR/config/messagetriggers/bridge.json" <<EOF
{
  "middlewareBase": "http://127.0.0.1:$STUB_PORT",
  "triggerPhrase": "@bot",
  "thinkingAck": "...thinking...",
  "failureMessage": "Message Triggers failed to answer.",
  "publicReplies": false,
  "sentMemory": 16
}
EOF

# ── 2. vanilla server jar (cached) + server dir ───────────────────────────
mkdir -p "$CACHE_DIR" "$SERVER_DIR"
SERVER_JAR="$CACHE_DIR/server-$MC_VERSION.jar"
if [ ! -f "$SERVER_JAR" ]; then
	log "downloading the vanilla $MC_VERSION server jar (~55 MB, one-time)"
	python3 - "$SERVER_JAR" "$MC_VERSION" <<'PYEOF'
import json, sys, urllib.request
out, version = sys.argv[1], sys.argv[2]
manifest = json.load(urllib.request.urlopen(
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
version_url = next(v["url"] for v in manifest["versions"] if v["id"] == version)
downloads = json.load(urllib.request.urlopen(version_url))["downloads"]
urllib.request.urlretrieve(downloads["server"]["url"], out)
PYEOF
fi
printf 'eula=true\n' > "$SERVER_DIR/eula.txt"
cat > "$SERVER_DIR/server.properties" <<EOF
online-mode=false
level-type=minecraft\\:flat
motd=e2e harness
view-distance=4
spawn-protection=0
sync-chunk-writes=false
white-list=false
EOF

# ── 3. stub middleware ────────────────────────────────────────────────────
python3 "$E2E_DIR/stub-middleware.py" "$STUB_PORT" "$RUN_DIR/stub-requests.jsonl" &
STUB_PID=$!
for _ in $(seq 1 40); do
	if (exec 3<>"/dev/tcp/127.0.0.1/$STUB_PORT") 2>/dev/null; then break; fi
	sleep 0.5
done
log "stub middleware listening on 127.0.0.1:$STUB_PORT"

# ── 4. vanilla server (the client needs a world for the chat HUD) ─────────
(cd "$SERVER_DIR" && "$JAVA" -Xmx1G -jar "$SERVER_JAR" nogui > console.log 2>&1) &
SERVER_PID=$!
log "waiting for the vanilla server to come up"
SERVER_UP=false
for _ in $(seq 1 "$SERVER_TIMEOUT_SECS"); do
	if grep -q "Done (" "$SERVER_DIR/logs/latest.log" 2>/dev/null; then
		SERVER_UP=true
		break
	fi
	kill -0 "$SERVER_PID" 2>/dev/null || break
	sleep 1
done
if [ "$SERVER_UP" != true ]; then
	log "ERROR: the vanilla server did not start — console tail:"
	tail -20 "$SERVER_DIR/console.log" 2>/dev/null || true
	exit 1
fi
log "vanilla server up"

# ── 5. client (loom run task for the e2e source set; driver stops it) ─────
log "launching the Minecraft client (first run downloads the game + assets)"
cd "$ROOT_DIR"
set +e
# </dev/null: the gradle client otherwise blocks forever on stdin forwarding
# after the build ends when its stdin is an interactive terminal.
timeout "$CLIENT_TIMEOUT_SECS" ./gradlew --no-daemon --console=plain runE2eClient </dev/null
CLIENT_EXIT=$?
set -e

# ── 6. verdict ────────────────────────────────────────────────────────────
RESULT="$RUN_DIR/e2e-result.json"
if [ ! -f "$RESULT" ]; then
	log "ERROR: the driver never wrote a result (client exit code $CLIENT_EXIT)"
	tail -40 "$RUN_DIR/logs/latest.log" 2>/dev/null || true
	exit 1
fi
if ! grep -q "bridge loaded: http://127.0.0.1:$STUB_PORT" "$RUN_DIR/logs/latest.log" 2>/dev/null; then
	log "FAIL: the 'bridge loaded' line is missing from the client log"
	exit 1
fi
python3 - "$RESULT" <<'PYEOF'
import json, sys
result = json.load(open(sys.argv[1]))
for check in result["checks"]:
    print(("PASS  " if check["pass"] else "FAIL  ") + check["name"])
print("E2E " + ("PASSED" if result["pass"] else "FAILED"))
sys.exit(0 if result["pass"] else 1)
PYEOF
