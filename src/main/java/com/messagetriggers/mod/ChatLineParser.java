package com.messagetriggers.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure chat-line logic, split out of Bridge so it can be unit-tested with
 * plain JUnit (no Minecraft classes, no game window):
 *
 *  - chat lines shaped "<player> message" are player chat; anything else
 *    (system messages, the bridge's own local echoes) is ignored
 *  - a message starting with the trigger phrase is a trigger
 *  - the middleware payload is {"sender": ..., "message": ...}
 *  - the middleware answers over the WS push channel, never the POST
 *    response (v2 contract) — there is no response-body parsing here
 */
public final class ChatLineParser {
	/** Same parse contract as the old ChatTriggers criteria "<${name}> ${message}". */
	static final Pattern CHAT_PATTERN = Pattern.compile("^<([^>]+)> (.*)$", Pattern.DOTALL);
	private static final Gson GSON = new Gson();

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

	/** Serializes the /api/chat payload; control characters are escaped. */
	public static String buildPayloadJson(String sender, String message) {
		JsonObject payload = new JsonObject();
		payload.addProperty("sender", sender);
		payload.addProperty("message", message);
		return GSON.toJson(payload);
	}
}
