package com.messagetriggers.mod;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * The chat → middleware bridge. Mirrors the ChatTriggers module contract
 * (contracts/api.md mod contract):
 *
 *  - Every chat line shaped "<player> message" is POSTed to /api/chat
 *    (fire-and-forget when it is not a trigger message — history ingestion).
 *  - Trigger messages show the thinking ack immediately, then echo the
 *    middleware's reply verbatim (200 answer, 429 busy, 504 timeout, 502
 *    failure all carry a human-readable reply).
 *  - HTTP is asynchronous and the response is marshalled back onto the
 *    client thread — a synchronous wait would freeze the game for the whole
 *    agent timeout.
 *  - With publicReplies enabled the reply is SENT to the server as the
 *    player (everyone in chat sees it); otherwise it only echoes locally.
 *    The thinking ack always stays local (it is transient noise).
 *  - Feedback loop guard: the server echo of a reply we sent comes back
 *    shaped "<player> message" like any other chat. Those echoes are still
 *    POSTed (so replies enter the chat history) but are never treated as
 *    triggers, even if the reply text happens to start with the trigger
 *    phrase.
 *
 * All tunables come from bridge.json via BridgeConfig (bundled defaults are
 * used when the file is missing — see BridgeConfig). Config can be changed
 * in-game via /mtriggers; MessageTriggersMod swaps in the updated bridge.
 */
public final class Bridge {
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private final BridgeConfig config;
	/** Lines we sent as the player; their echoes must never re-trigger. */
	private final ArrayDeque<String> sentLines = new ArrayDeque<>();

	private Bridge(BridgeConfig config) {
		this.config = config;
	}

	/** Loads bridge.json (bundled defaults when the file is missing). */
	public static Bridge load(Path configPath) {
		BridgeConfig config = BridgeConfig.load(configPath);
		return config == null ? null : new Bridge(config);
	}

	/** Wraps an already-loaded config (used when /mtriggers applies changes). */
	public static Bridge fromConfig(BridgeConfig config) {
		return new Bridge(config);
	}

	public BridgeConfig config() {
		return config;
	}

	public String middlewareBase() {
		return config.middlewareBase();
	}

	public String triggerPhrase() {
		return config.triggerPhrase();
	}

	/**
	 * Middleware push (WS channel, local mode): echo into the local HUD only.
	 * publicReplies does not apply — the middleware picked the mode explicitly.
	 */
	public void pushLocal(String text) {
		say(text);
	}

	/**
	 * Middleware push (WS channel, public mode): same display rules as a
	 * trigger reply — sent to the server as the player when publicReplies is
	 * on, locally echoed otherwise (the player has final say over whether
	 * anything leaves their machine).
	 */
	public void pushPublic(String text) {
		deliver(text);
	}

	public void onChatMessage(String rawText) {
		String[] parsed = ChatLineParser.parse(rawText);
		if (parsed == null) {
			return;
		}
		String sender = parsed[0];
		String message = parsed[1];

		// Echo of a reply we sent as the player: log it as history but never
		// let it act as a trigger (even if it starts with the trigger phrase).
		if (sentLines.remove(message)) {
			post(sender, message, null);
			return;
		}

		boolean triggered = ChatLineParser.isTrigger(message, config.triggerPhrase());

		if (!triggered) {
			// Fire-and-forget: history ingestion only, response ignored.
			post(sender, message, null);
			return;
		}

		// Tagged: show the thinking ack immediately, then echo the reply.
		say(config.thinkingAck());
		post(sender, message, this::onReplyBody);
	}

	private void post(String sender, String message, Consumer<String> onBody) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(config.middlewareBase() + "/api/chat"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(ChatLineParser.buildPayloadJson(sender, message)))
				.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.whenComplete((response, error) -> MinecraftClient.getInstance().execute(() -> {
					if (error != null) {
						MessageTriggersMod.LOGGER.error("middleware request failed: {}", error.toString());
						if (onBody != null) {
							say(config.failureMessage());
						}
						return;
					}
					if (onBody != null) {
						onBody.accept(response.body());
					}
				}));
	}

	private void onReplyBody(String body) {
		// The middleware attaches a human-readable reply to every status
		// (200 answer, 429 busy, 504 timeout, 502 failure) — show it verbatim.
		String reply = ChatLineParser.extractReply(body);
		deliver(reply != null ? reply : config.failureMessage());
	}

	/**
	 * Delivers a bot reply: sent to the server as the player when
	 * publicReplies is on (everyone sees it), otherwise echoed locally only.
	 * Multi-line replies are sent as one chat message per line (chat cannot
	 * carry newlines). The middleware's responseMaxLength (central config)
	 * keeps each line within the server's 256-char chat limit.
	 *
	 * Pushes can and do arrive before a world is joined (the title screen —
	 * the WS channel connects at boot), so "send as player" degrades to a
	 * local echo there: it must never crash the game or lose the message.
	 */
	private void deliver(String text) {
		MinecraftClient client = MinecraftClient.getInstance();
		boolean inWorld = client.player != null;
		if (!config.publicReplies() || !inWorld) {
			if (config.publicReplies() && !inWorld) {
				MessageTriggersMod.LOGGER.warn(
						"public reply arrived before a world was joined — echoing locally instead");
			}
			say(text);
			return;
		}
		for (String line : text.split("\n", -1)) {
			if (line.isEmpty()) {
				continue;
			}
			client.player.networkHandler.sendChatMessage(line);
			sentLines.addLast(line);
			while (sentLines.size() > config.sentMemory()) {
				sentLines.removeFirst();
			}
		}
	}

	private void say(String text) {
		MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(text));
	}
}
