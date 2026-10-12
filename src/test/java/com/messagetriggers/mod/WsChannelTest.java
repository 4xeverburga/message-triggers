package com.messagetriggers.mod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the push channel's pure logic (no sockets, no Minecraft):
 *
 * URI derivation: {middlewareBase} → its ws[s] form + /api/ws
 * (same origin the bridge POSTs to — one host, one trust decision).
 *
 * Frame parsing: {"text": "...", "mode": "local"|"public"} — mode optional,
 * default local. Anything malformed, ambiguous or empty is DROPPED (null),
 * never partially displayed.
 */
class WsChannelTest {
	// ── wsUri ──────────────────────────────────────────────────────────────

	@Test
	void httpBecomesWs() {
		assertEquals("ws://localhost:8080/api/ws", WsChannel.wsUri("http://localhost:8080").toString());
	}

	@Test
	void httpsBecomesWss() {
		assertEquals("wss://bridge.example.com/api/ws", WsChannel.wsUri("https://bridge.example.com").toString());
	}

	@Test
	void baseURLGetsTheDefaultPath() {
		assertEquals("ws://127.0.0.1:3000/api/ws", WsChannel.wsUri("http://127.0.0.1:3000").toString());
	}

	@Test
	void trailingSlashAndConfiguredPathAreKept() {
		assertEquals("ws://127.0.0.1:32100/agent/api/ws", WsChannel.wsUri("http://127.0.0.1:32100/agent/").toString());
	}

	// ── parse ──────────────────────────────────────────────────────────────

	@Test
	void parsesLocalAndPublicModes() {
		assertFalse(WsChannel.parse("{\"text\": \"hello\", \"mode\": \"local\"}").isPublic());
		assertTrue(WsChannel.parse("{\"text\": \"hello\", \"mode\": \"public\"}").isPublic());
	}

	@Test
	void modeIsOptionalAndDefaultsToLocal() {
		WsChannel.Push push = WsChannel.parse("{\"text\": \"just text\"}");
		assertEquals("just text", push.text());
		assertFalse(push.isPublic());
	}

	@Test
	void unknownModeDropsTheFrame() {
		assertNull(WsChannel.parse("{\"text\": \"x\", \"mode\": \"anything-else\"}"));
		assertNull(WsChannel.parse("{\"text\": \"x\", \"mode\": \"PUBLIC\"}"));
		assertNull(WsChannel.parse("{\"text\": \"x\", \"mode\": 4}"));
	}

	@Test
	void dropsEverythingMalformed() {
		assertNull(WsChannel.parse(null));
		assertNull(WsChannel.parse(""));
		assertNull(WsChannel.parse("not json"));
		assertNull(WsChannel.parse("{\"noText\": true}"));
		assertNull(WsChannel.parse("{\"text\": null}"));
		assertNull(WsChannel.parse("{\"text\": \"   \"}"));
		assertNull(WsChannel.parse("[\"text\"]"));
		assertNull(WsChannel.parse("123"));
	}
}
