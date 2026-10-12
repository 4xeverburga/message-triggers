package com.messagetriggers.mod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Contract tests for the WS channel's pure logic (no sockets, no Minecraft):
 *
 * URI derivation: {middlewareBase} → its ws[s] form + /api/ws
 * (one origin, one trust decision — contract v4 has NO other endpoint).
 *
 * Frame parsing: {"text": "...", "mode": "local"|"public"|"localStream"} —
 * mode optional, default local. Anything malformed, ambiguous or empty is
 * DROPPED (null), never partially displayed.
 *
 * Outbound frame building: {"type": "chat", "sender", "message"}.
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
	void parsesAllThreeModes() {
		assertEquals(WsChannel.Mode.LOCAL,
				WsChannel.parse("{\"text\": \"hello\", \"mode\": \"local\"}").mode());
		assertEquals(WsChannel.Mode.PUBLIC,
				WsChannel.parse("{\"text\": \"hello\", \"mode\": \"public\"}").mode());
		assertEquals(WsChannel.Mode.LOCAL_STREAM,
				WsChannel.parse("{\"text\": \"hel\", \"mode\": \"localStream\"}").mode());
	}

	@Test
	void modeIsOptionalAndDefaultsToLocal() {
		WsChannel.Push push = WsChannel.parse("{\"text\": \"just text\"}");
		assertEquals("just text", push.text());
		assertEquals(WsChannel.Mode.LOCAL, push.mode());
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

	// ── buildChatFrame ─────────────────────────────────────────────────────

	@Test
	void buildsTypedChatFrames() {
		assertEquals("{\"type\":\"chat\",\"sender\":\"4verburg\",\"message\":\"hello there\"}",
				WsChannel.buildChatFrame("4verburg", "hello there"));
	}

	@Test
	void chatFrameEscapingSurvivesRoundTrip() {
		// Quotes, backslashes and newlines must survive the JSON encoding.
		String frame = WsChannel.buildChatFrame("alice", "say \"hi\" \\ o/\nnice");
		assertEquals("{\"type\":\"chat\",\"sender\":\"alice\",\"message\":\"say \\\"hi\\\" \\\\ o/\\nnice\"}", frame);
	}
}
