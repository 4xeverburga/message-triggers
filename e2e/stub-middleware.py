#!/usr/bin/env python3
"""Deterministic middleware stub for the message-triggers e2e harness.

Implements both contracts the bridge expects (docs/middleware-contract.md),
served by aiohttp on ONE port (same-origin, like a real middleware):
  POST /api/chat:
    - untagged message  → 202 {"status": "logged"}
    - tagged message    → 200 {"reply": "E2E-REPLY-OK", "role": "owner"}
    - tagged + "failme" → 502 {"reply": "STUB-FAILURE-REPLY"}
  GET /api/ws: WebSocket endpoint that pushes two frames (one local-mode,
    one public-mode push, {"text": ..., "mode": ...}) the moment a client
    connects, then stays open ignoring client frames (the mod is
    listen-only in v1).

Every received HTTP payload is appended (JSONL) to the file given as
argv[2] so the driver mod can assert the bridge actually sent the right lines.

Requires aiohttp (pip install aiohttp).
Usage: stub-middleware.py [port] [request-log-path]
"""
import asyncio
import datetime
import json
import sys
from aiohttp import web

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 32100
REQUEST_LOG = sys.argv[2] if len(sys.argv) > 2 else "stub-requests.jsonl"
TRIGGER = "@bot"
REPLY = "E2E-REPLY-OK"
FAILURE_REPLY = "STUB-FAILURE-REPLY"
WS_LOCAL = "WS-LOCAL-MSG: the middleware says hi"
WS_PUBLIC = "WS-PUBLIC-MSG: pushed to the world"


async def chat(request: web.Request) -> web.Response:
    body = await request.text()
    try:
        payload = json.loads(body)
    except Exception:
        payload = {}
    sender = str(payload.get("sender", ""))
    message = str(payload.get("message", ""))
    with open(REQUEST_LOG, "a") as log:
        log.write(json.dumps({
            "ts": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "sender": sender,
            "message": message,
        }) + "\n")
    if message.startswith(TRIGGER):
        status, reply = (502, FAILURE_REPLY) if "failme" in message else (200, REPLY)
        data = {"reply": reply, "role": "owner"}
    else:
        status, data = 202, {"status": "logged"}
    return web.json_response(data, status=status)


async def ws(request: web.Request) -> web.WebSocketResponse:
    sock = web.WebSocketResponse()
    await sock.prepare(request)
    # The first push lands while the client is still on its title screen
    # (exercises the mod's not-in-a-world fallback); later repeats land in
    # a joined world (exercises the public path: sent to the server AS the
    # player and echoed back by the server).
    await sock.send_json({"text": WS_LOCAL, "mode": "local"})
    while True:
        try:
            await asyncio.wait_for(sock.receive(), timeout=5.0)
            if sock.closed:
                break
        except asyncio.TimeoutError:
            await sock.send_json({"text": WS_PUBLIC, "mode": "public"})
        except ConnectionResetError:
            break
    return sock


app = web.Application()
app.router.add_post("/api/chat", chat)
app.router.add_get("/api/ws", ws)
web.run_app(app, host="127.0.0.1", port=PORT, print=None)
