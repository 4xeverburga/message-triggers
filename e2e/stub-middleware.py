#!/usr/bin/env python3
"""Deterministic middleware stub for the message-triggers e2e harness.

Implements contract v3 (docs/middleware-contract.md) — ONE surface, the
WebSocket, served by aiohttp on ONE port (same-origin, like a real
middleware). There is NO /api/chat: the mod forwards nothing over HTTP.

  GET /api/ws — bidirectional:
    client→server frames: {"type": "chat", "sender", "message"} — every
      chat line the client renders is appended (JSONL) to the file given
      as argv[2] so the driver mod can assert the bridge actually sent
      the right lines.
    server→client frames: replies/pushes. A tagged message (@bot ...)
      queues a reply ({"failme" in the message queues STUB-FAILURE-REPLY
      to exercise the failure case); the loop drains the queue as
      {"text": reply, "mode": "public"} frames, which the mod then sends
      to the server AS the player (publicReplies=true in the harness
      config). On connect the stub pushes one local-mode frame
      immediately (lands before a world is joined — exercises the mod's
      not-in-a-world fallback), then repeats a public-mode ping every 1s
      tick (exercises the sent-to-server path and its echo back as chat
      frames — the feedback loop).

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

# Replies owed: queued on inbound chat frames, drained by the WS loop.
# Contract v3 says the transport is fire-and-forget, the middleware owns
# queue-and-discipline — the queue models exactly that.
pending_replies: list[str] = []


async def ws(request: web.Request) -> web.WebSocketResponse:
    sock = web.WebSocketResponse()
    await sock.prepare(request)
    # The first push lands while the client is still on its title screen
    # (exercises the mod's not-in-a-world fallback); later repeats land in
    # a joined world (exercise the public path: sent to the server AS the
    # player and echoed back by the server).
    await sock.send_json({"text": WS_LOCAL, "mode": "local"})
    # One loop, two duties: handle inbound chat frames as they arrive, and
    # every tick drain the reply queue + repeat the public ping (drives
    # both the reply path and the feedback-loop echo frames).
    while True:
        try:
            msg = await asyncio.wait_for(sock.receive(), timeout=1.0)
            if msg.type == web.WSMsgType.TEXT:
                handle_chat_frame(msg.data)
            elif msg.type in (web.WSMsgType.CLOSE, web.WSMsgType.CLOSING,
                              web.WSMsgType.CLOSED, web.WSMsgType.ERROR):
                break
        except asyncio.TimeoutError:
            while pending_replies:
                await sock.send_json({"text": pending_replies.pop(0), "mode": "public"})
            await sock.send_json({"text": WS_PUBLIC, "mode": "public"})
        except ConnectionResetError:
            break
    return sock


def handle_chat_frame(raw: str) -> None:
    global pending_replies
    try:
        frame = json.loads(raw)
    except Exception:
        return
    sender = str(frame.get("sender", ""))
    message = str(frame.get("message", ""))
    with open(REQUEST_LOG, "a") as log:
        log.write(json.dumps({
            "ts": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "sender": sender,
            "message": message,
        }) + "\n")
    if message.startswith(TRIGGER):
        pending_replies.append(FAILURE_REPLY if "failme" in message else REPLY)


app = web.Application()
app.router.add_get("/api/ws", ws)
web.run_app(app, host="127.0.0.1", port=PORT, print=None)
