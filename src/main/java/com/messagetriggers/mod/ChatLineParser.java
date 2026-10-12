package com.messagetriggers.mod;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure chat-line logic, split out of Bridge so it can be unit-tested with
 * plain JUnit (no Minecraft classes, no game window):
 *
 *  - chat lines shaped "<player> message" are player chat; anything else
 *    (system messages, the bridge's own local echoes) is ignored
 *  - a message starting with the trigger phrase is a trigger (client-side
 *    decoration only — the middleware decides what to answer)
 *  - the outbound WS frame {"type": "chat", "sender", "message"} is built
 *    by WsChannel.buildChatFrame (contract v3 — no HTTP anywhere)
 */
public final class ChatLineParser {
	/** Same parse contract as the old ChatTriggers criteria "<${name}> ${message}". */
	static final Pattern CHAT_PATTERN = Pattern.compile("^<([^>]+)> (.*)$", Pattern.DOTALL);

	private ChatLineParser() {
	}

	/** Returns [sender, message] for player chat lines, or null for anything else. */
	public static String[] parse(String rawText) {
		if (rawText == null) {
			return null;
		}
		Matcher matcher = CHAT_PATTERN.matcher(rawText);
		if (!matcher.matches()) {
			return null;
		}
		return new String[] { matcher.group(1), matcher.group(2) };
	}

	public static boolean isTrigger(String message, String triggerPhrase) {
		return message.startsWith(triggerPhrase);
	}
}
