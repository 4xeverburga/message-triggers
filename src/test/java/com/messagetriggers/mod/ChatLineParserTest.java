package com.messagetriggers.mod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the pure chat-line logic (no Minecraft classes).
 * Scenarios mirror the middleware contract (docs/middleware-contract.md).
 */
class ChatLineParserTest {
	@Test
	void parsesPlayerChatLines() {
		String[] parsed = ChatLineParser.parse("<4verburg> @bot hello");
		assertEquals("4verburg", parsed[0]);
		assertEquals("@bot hello", parsed[1]);
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
		assertNull(ChatLineParser.parse("...thinking..."));
		assertNull(ChatLineParser.parse("Message Triggers failed to answer."));
		assertNull(ChatLineParser.parse("<only-a-name-no-message"));
		assertNull(ChatLineParser.parse(""));
		assertNull(ChatLineParser.parse(null));
	}

	@Test
	void detectsTriggerByPrefix() {
		assertTrue(ChatLineParser.isTrigger("@bot hello", "@bot"));
		assertTrue(ChatLineParser.isTrigger("@bot", "@bot"));
		assertFalse(ChatLineParser.isTrigger("hello @bot", "@bot"));
		assertFalse(ChatLineParser.isTrigger("@botx hi", "@bot ")); // phrase includes trailing space
	}
}
