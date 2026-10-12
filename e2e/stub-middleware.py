#!/usr/bin/env python3
"""Deterministic middleware stub for the message-triggers e2e harness.

Implements the POST /api/chat contract the bridge expects (contracts/api.md):
  - untagged message  → 202 {"status": "logged"}
  - tagged message    → 200 {"reply": "E2E-REPLY-OK", "role": "owner"}
  - tagged + "failme" → 502 {"reply": "STUB-FAILURE-REPLY"}

Every received payload is appended (JSONL) to the file given as argv[2] so
the driver mod can assert the bridge actually sent the right lines.

Usage: stub-middleware.py [port] [request-log-path]
"""
import datetime
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 32100
REQUEST_LOG = sys.argv[2] if len(sys.argv) > 2 else "stub-requests.jsonl"
TRIGGER = "@bot"
REPLY = "E2E-REPLY-OK"
FAILURE_REPLY = "STUB-FAILURE-REPLY"


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8")
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
            if "failme" in message:
                status, reply = 502, FAILURE_REPLY
            else:
                status, reply = 200, REPLY
            data = json.dumps({"reply": reply, "role": "owner"}).encode()
        else:
            status, data = 202, json.dumps({"status": "logged"}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass  # keep the harness console quiet


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
