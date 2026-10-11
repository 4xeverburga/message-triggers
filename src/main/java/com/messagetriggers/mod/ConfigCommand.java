package com.messagetriggers.mod;

import java.nio.file.Path;

/**
 * Pure logic for the in-game /mtriggers config command (no Minecraft
 * classes). MessageTriggersMod registers the brigadier tree and persists
 * whatever config this handler returns.
 *
 * Grammar:
 *   (empty) | show   -> list every tunable with its current value
 *   set <key> <value...> -> validate + return the updated config
 *   reset            -> return the bundled defaults
 *   path             -> report the bridge.json location
 */
public final class ConfigCommand {
	/**
	 * @param success  whether the command was understood and applied
	 * @param feedback human-readable text to show in chat
	 * @param config   the new config to persist, or null when nothing changed
	 */
	public record Result(boolean success, String feedback, BridgeConfig config) {
	}

	private ConfigCommand() {
	}

	public static Result handle(String args, BridgeConfig current, Path configPath) {
		String trimmed = args == null ? "" : args.trim();
		if (trimmed.isEmpty() || trimmed.equals("show")) {
			return new Result(true, summary(current), null);
		}
		if (trimmed.equals("path")) {
			return new Result(true, configPath.toString(), null);
		}
		if (trimmed.equals("reset")) {
			BridgeConfig defaults = BridgeConfig.loadDefaults();
			if (defaults == null) {
				return new Result(false, "bundled defaults are unreadable — reinstall the mod", null);
			}
			return new Result(true, "config reset to bundled defaults", defaults);
		}
		if (trimmed.startsWith("set ")) {
			return handleSet(trimmed.substring("set ".length()), current);
		}
		return new Result(false, usage(), null);
	}

	private static Result handleSet(String rest, BridgeConfig current) {
		int keyEnd = rest.indexOf(' ');
		if (keyEnd < 0) {
			return new Result(false, usage(), null);
		}
		String key = rest.substring(0, keyEnd);
		String value = rest.substring(keyEnd + 1).trim();
		if (value.isEmpty()) {
			return new Result(false, usage(), null);
		}
		try {
			BridgeConfig updated = current.with(key, value);
			return new Result(true, key + " set to " + value, updated);
		} catch (IllegalArgumentException err) {
			return new Result(false, err.getMessage() + " — " + usage(), null);
		}
	}

	private static String summary(BridgeConfig config) {
		StringBuilder text = new StringBuilder();
		for (String key : BridgeConfig.keys()) {
			if (text.length() > 0) {
				text.append('\n');
			}
			text.append(key).append(" = ").append(valueOf(config, key));
		}
		return text.toString();
	}

	private static String valueOf(BridgeConfig config, String key) {
		return switch (key) {
			case "middlewareBase" -> config.middlewareBase();
			case "triggerPhrase" -> config.triggerPhrase();
			case "thinkingAck" -> config.thinkingAck();
			case "failureMessage" -> config.failureMessage();
			case "publicReplies" -> Boolean.toString(config.publicReplies());
			case "sentMemory" -> Integer.toString(config.sentMemory());
			default -> throw new IllegalArgumentException("unknown key: " + key);
		};
	}

	private static String usage() {
		return "usage: /mtriggers show | set <key> <value> | reset | path (keys: "
				+ String.join(", ", BridgeConfig.keys()) + ")";
	}
}
