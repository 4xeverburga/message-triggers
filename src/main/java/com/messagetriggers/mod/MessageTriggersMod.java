package com.messagetriggers.mod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Client mod entrypoint. Loads bridge.json (bundled defaults when the file
 * is missing — standalone installs work out of the box) and exposes the
 * bridge to the chat mixin. Registers /mtriggers so every tunable can be
 * changed in-game and persisted back to bridge.json.
 */
public final class MessageTriggersMod implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("messagetriggers");

	private static Bridge bridge;
	private static Path configPath;
	private static WsChannel wsChannel;

	@Override
	public void onInitializeClient() {
		configPath = FabricLoader.getInstance().getConfigDir()
				.resolve("messagetriggers")
				.resolve("bridge.json");
		bridge = Bridge.load(configPath, MessageTriggersMod::onOutgoingChat);
		if (bridge == null) {
			LOGGER.error("bridge config could not be loaded (defaults resource unreadable) at {}", configPath);
			return;
		}
		if (BridgeConfig.loadDefaults() != null && !java.nio.file.Files.exists(configPath)) {
			LOGGER.info("bridge.json not found at {} — using bundled defaults (change in-game via /mtriggers)", configPath);
		}
		LOGGER.info("bridge loaded: {} (trigger {})", bridge.middlewareBase(), bridge.triggerPhrase());
		reconnectWsChannel(bridge.config());
		registerCommands();
	}

	private static void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("mtriggers")
						.then(ClientCommandManager.literal("show")
								.executes(ctx -> run(ctx.getSource(), "show")))
						.then(ClientCommandManager.literal("set")
								.then(ClientCommandManager.argument("key", StringArgumentType.word())
										.then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
												.executes(ctx -> run(ctx.getSource(), "set "
														+ StringArgumentType.getString(ctx, "key") + " "
														+ StringArgumentType.getString(ctx, "value"))))))
						.then(ClientCommandManager.literal("reset")
								.executes(ctx -> run(ctx.getSource(), "reset")))
						.then(ClientCommandManager.literal("path")
								.executes(ctx -> run(ctx.getSource(), "path")))));
	}

	/** Applies a /mtriggers result: persist first, then swap the live bridge. */
	private static int run(FabricClientCommandSource source, String args) {
		ConfigCommand.Result result = ConfigCommand.handle(args, bridge.config(), configPath);
		if (result.config() != null) {
			try {
				result.config().save(configPath);
			} catch (IOException err) {
				source.sendFeedback(Text.literal("could not save " + configPath + ": " + err));
				return 0;
			}
			bridge = Bridge.fromConfig(result.config(), MessageTriggersMod::onOutgoingChat);
			LOGGER.info("config updated via /mtriggers: {}", result.feedback().replace('\n', ';'));
			reconnectWsChannel(result.config());
		}
		source.sendFeedback(Text.literal(result.feedback()));
		return result.success() ? 1 : 0;
	}

	/**
	 * Reconnects the WS channel when the config changes — routing bridge
	 * swaps replace the bridge (same outbox wiring), and a changed
	 * middlewareBase means the channel must point at the new origin at
	 * set-time without a restart. The channel itself is ALWAYS on: it is
	 * the mod's only network surface (contract v3).
	 */
	private static void reconnectWsChannel(BridgeConfig config) {
		if (wsChannel != null) {
			wsChannel.stop();
			wsChannel = null;
		}
		wsChannel = new WsChannel(config.middlewareBase(), LOGGER, MessageTriggersMod::onPush);
		wsChannel.start();
	}

	/**
	 * Push sink for the ws channel (called on the WS thread): hops onto the
	 * client thread and routes to the current bridge.
	 */
	public static void onPush(String text, boolean isPublic) {
		MinecraftClient.getInstance().execute(() -> {
			if (bridge == null) {
				return;
			}
			if (isPublic) {
				bridge.pushPublic(text);
			} else {
				bridge.pushLocal(text);
			}
		});
	}

	/**
	 * Outbox for the bridge (called on the client thread): sends a chat
	 * line over the current WS channel; dropped when disconnected.
	 */
	private static void onOutgoingChat(String sender, String message) {
		if (wsChannel != null) {
			wsChannel.sendChat(sender, message);
		}
	}

	/** Called by the chat mixin for every chat line rendered in the HUD. */
	public static void onChatLine(String rawText) {
		if (bridge == null) {
			return;
		}
		bridge.onChatMessage(rawText);
	}
}
