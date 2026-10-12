#!/usr/bin/env python3
"""Deterministic middleware stub for the message-triggers e2e harness.

Implements contract v2 (docs/middleware-contract.md) served by aiohttp on
ONE port (same-origin, like a real middleware):

  POST /api/chat: one-way notification — every payload is logged and
    answered with a fast 202 {"status": "logged"}; the body is never read
    by the mod, so nothing replies over the POST response.
  GET /api/ws: WebSocket push channel — THE answer surface. A tagged
    trigger (@bot ...) queues a reply ({"failme" in the message queues
    STUB-FAILURE-REPLY to exercise the failure case); the WS loop drains
    the queue by pushing {"text": reply, "mode": "public"} frames, which
    the mod then sends to the server AS the player (publicReplies=true in
    the harness config). On connect the stub also pushes one local-mode
    frame immediately (lands before a world is joined — exercises the
    mod's not-in-a-world fallback), then repeats a public-mode ping every
    5s (exercises the sent-to-server path and its echo back as chat POSTs).

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

# Replies owed: appended by the chat POST handler, drained by the WS loop.
# Contract v2 says pushes are fire-and-forget from the middleware's side —
# the queue models a middleware that answers every trigger it saw.
pending_replies: list[str] = []


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
        pending_replies.append(FAILURE_REPLY if "failme" in message else REPLY)
    return web.json_response({"status": "logged"}, status=202)


async def ws(request: web.Request) -> web.WebSocketResponse:
    sock = web.WebSocketResponse()
    await sock.prepare(request)
    # The first push lands while the client is still on its title screen
    # (exercises the mod's not-in-a-world fallback); later repeats land in
    # a joined world (exercise the public path: sent to the server AS the
    # player and echoed back by the server).
    await sock.send_json({"text": WS_LOCAL, "mode": "local"})
    while True:
        try:
            await asyncio.wait_for(sock.receive(), timeout=1.0)
            if sock.closed:
                break
        except asyncio.TimeoutError:
            while pending_replies:
                await sock.send_json({"text": pending_replies.pop(0), "mode": "public"})
            await sock.send_json({"text": WS_PUBLIC, "mode": "public"})
        except ConnectionResetError:
            break
    return sock


app = web.Application()
app.router.add_post("/api/chat", chat)
app.router.add_get("/api/ws", ws)
web.run_app(app, host="127.0.0.1", port=PORT, print=None)
