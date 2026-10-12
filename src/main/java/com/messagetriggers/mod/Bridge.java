package com.messagetriggers.mod;

import com.messagetriggers.mod.mixin.ChatHudAccessor;
import com.messagetriggers.mod.mixin.ChatHudInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.List;

/**
 * The chat ⇄ middleware bridge (contract v4): ONE channel, the WebSocket.
 *
 *  - Every chat line shaped "<player> message" is forwarded to the
 *    middleware as a {"type": "chat"} frame on the WS (WsChannel.sendChat),
 *    fire-and-forget: when the socket is down the line is dropped — the
 *    middleware's queue-and-discipline problem, mirror side of the pushes.
 *    There is no HTTP anywhere in this mod and no client-side trigger
 *    concept: what to answer, and when, is entirely the middleware's call.
 *  - Messages that come back (WS frames, any time) are displayed by
 *    pushLocal/pushPublic: public frames are sent to the server as the
 *    player when publicReplies is on, local frames echo into the HUD.
 *  - localStream frames stream text: consecutive chunks accumulate into ONE
 *    local HUD line that is re-rendered in place as each chunk arrives, so
 *    a long answer starts appearing before it is finished. Streaming is
 *    local-only — it never sends anything to the server. Any normal
 *    (local/public) push closes the active stream.
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

	/** Accumulated text of the active stream, or null when none is open. */
	private String streamText;
	/** The HUD line currently backing the active stream (replaced in place). */
	private ChatHudLine streamLine;

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

	/**
	 * Middleware push (WS channel, local mode): echo into the local HUD only.
	 * publicReplies does not apply — the middleware picked the mode explicitly.
	 */
	public void pushLocal(String text) {
		closeStream();
		say(text);
	}

	/**
	 * Middleware push (WS channel, public mode): same display rules as any
	 * reply — sent to the server as the player when publicReplies is on,
	 * locally echoed otherwise (the player has final say over whether
	 * anything leaves their machine).
	 */
	public void pushPublic(String text) {
		closeStream();
		deliver(text);
	}

	/**
	 * One localStream chunk (WS channel): appended to the active stream's
	 * text, which is re-rendered as ONE HUD line. The first chunk of a
	 * stream opens the line; a later normal push closes it (closeStream).
	 * Local display only — streaming never sends anything to the server.
	 */
	public void pushStreamChunk(String chunk) {
		streamText = streamText == null ? chunk : streamText + chunk;
		ChatHud hud = MinecraftClient.getInstance().inGameHud.getChatHud();
		ChatHudLine updated = new ChatHudLine(
				MinecraftClient.getInstance().inGameHud.getTicks(),
				Text.literal(streamText), null, null);
		List<ChatHudLine> messages = ((ChatHudAccessor) hud).getMessages();
		if (streamLine != null) {
			int index = messages.lastIndexOf(streamLine);
			if (index >= 0) {
				messages.set(index, updated);
			} else {
				messages.add(updated);
			}
		} else {
			messages.add(updated);
		}
		((ChatHudInvoker) hud).invokeRefresh();
		streamLine = updated;
	}

	private void closeStream() {
		streamText = null;
		streamLine = null;
	}

	public void onChatMessage(String rawText) {
		String[] parsed = ChatLineParser.parse(rawText);
		if (parsed == null) {
			return;
		}
		outbox.sendChat(parsed[0], parsed[1]);
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
		}
	}

	private void say(String text) {
		MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(text));
	}
}
