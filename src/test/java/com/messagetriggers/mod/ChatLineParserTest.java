package com.messagetriggers.mod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Contract tests for the pure chat-line logic (no Minecraft classes).
 * Scenarios mirror the middleware contract (docs/middleware-contract.md).
 */
class ChatLineParserTest {
	@Test
	void parsesPlayerChatLines() {
		String[] parsed = ChatLineParser.parse("<4verburg> hello");
		assertEquals("4verburg", parsed[0]);
		assertEquals("hello", parsed[1]);
	}

	@Test
	void parsesMultilineMessages() {
		String[] parsed = ChatLineParser.parse("<alice> line one\nline two");
		assertEquals("alice", parsed[0]);
		assertEquals("line one\nline two", parsed[1]);
	}

	@Test
	void ignoresSystemAndLocalEchoLines() {
		// The bridge's own echoes and system messages have no <name> prefix —
		// this is what prevents the feedback loop.
		assertNull(ChatLineParser.parse("Message Triggers failed to answer."));
		assertNull(ChatLineParser.parse("<only-a-name-no-message"));
		assertNull(ChatLineParser.parse(""));
		assertNull(ChatLineParser.parse(null));
	}
}
