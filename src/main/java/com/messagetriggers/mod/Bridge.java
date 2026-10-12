package com.messagetriggers.mod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * The chat ⇄ middleware bridge (contract v3): ONE channel, the WebSocket.
 *
 *  - Every chat line shaped "<player> message" is forwarded to the
 *    middleware as a {"type": "chat"} frame on the WS (WsChannel.sendChat),
 *    fire-and-forget: when the socket is down the line is dropped — the
 *    middleware's queue-and-discipline problem, mirror side of the pushes.
 *    There is no HTTP anywhere in this mod.
 *  - Messages that come back (WS frames, any time) are displayed by
 *    deliver(): sent to the server as the player when publicReplies is on,
 *    local echo otherwise.
 *  - The thinking ack is purely client-side decoration: a line starting
 *    with the trigger phrase shows it the moment it is typed. It is the
 *    middleware's decision (from the forwarded lines) what to answer.
 *  - Feedback loop guard: the server echo of a message we sent comes back
 *    shaped "<player> message" like any other chat. Those echoes are still
 *    forwarded (so replies enter the history) but are never treated as
 *    triggers, even if the text happens to start with the trigger phrase.
 *
 * All tunables come from bridge.json via BridgeConfig (bundled defaults are
 * used when the file is missing — see BridgeConfig). Config can be changed
 * in-game via /mtriggers; MessageTriggersMod swaps in the updated bridge.
 */
public final class Bridge {
	/** Carrier for chat lines to the middleware (the WS channel). */
	public interface Outbox {
		void sendChat(String sender, String message);
	}

	private final BridgeConfig config;
	private final Outbox outbox;
	/** Lines we sent as the player; their echoes must never re-trigger. */
	private final ArrayDeque<String> sentLines = new ArrayDeque<>();

	private Bridge(BridgeConfig config, Outbox outbox) {
		this.config = config;
		this.outbox = outbox;
	}

	/** Loads bridge.json (bundled defaults when the file is missing). */
	public static Bridge load(Path configPath, Outbox outbox) {
		BridgeConfig config = BridgeConfig.load(configPath);
		return config == null ? null : new Bridge(config, outbox);
	}

	/** Wraps an already-loaded config (used when /mtriggers applies changes). */
	public static Bridge fromConfig(BridgeConfig config, Outbox outbox) {
		return new Bridge(config, outbox);
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
	 * Middleware push (WS channel, public mode): same display rules as any
	 * reply — sent to the server as the player when publicReplies is on,
	 * locally echoed otherwise (the player has final say over whether
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

		// Echo of a message we sent as the player: forward it as history but
		// never let it act as a trigger (even if it starts with the trigger
		// phrase).
		boolean echo = sentLines.remove(message);
		if (!echo && ChatLineParser.isTrigger(message, config.triggerPhrase())) {
			say(config.thinkingAck());
		}
		outbox.sendChat(sender, message);
	}

	/**
	 * Delivers a middleware message: sent to the server as the player when
	 * publicReplies is on (everyone sees it), otherwise echoed locally only.
	 * Multi-line messages are sent as one chat message per line (chat cannot
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
						"public push arrived before a world was joined — echoing locally instead");
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
