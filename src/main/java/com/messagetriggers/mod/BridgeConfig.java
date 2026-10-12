package com.messagetriggers.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The bridge's tunables, loaded from bridge.json next to the game config.
 * Pure logic (no Minecraft classes) so it can be unit-tested with plain
 * JUnit.
 *
 * Standalone behavior: when bridge.json is missing or malformed the bundled
 * defaults resource (/bridge.default.json) is used instead — a standalone
 * install works out of the box, and every value can then be changed in-game
 * via /mtriggers and persisted back to bridge.json. Defaults live in the
 * resource file, never in code.
 */
public final class BridgeConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("messagetriggers");
	private static final Gson GSON = new Gson();
	/** Bundled fallback config for standalone installs (no launcher). */
	static final String DEFAULTS_RESOURCE = "/bridge.default.json";

	private final String middlewareBase;
	private final boolean publicReplies;

	private BridgeConfig(String middlewareBase, boolean publicReplies) {
		this.middlewareBase = middlewareBase;
		this.publicReplies = publicReplies;
	}

	/** Every tunable, in display order (used by /mtriggers show and tests). */
	public static List<String> keys() {
		return List.of("middlewareBase", "publicReplies");
	}

	/**
	 * Loads bridge.json; falls back to the bundled defaults when the file is
	 * missing or malformed. Returns null only if the defaults resource itself
	 * is unreadable (a broken jar).
	 */
	public static BridgeConfig load(Path configPath) {
		String raw = readOrNull(configPath);
		if (raw != null) {
			try {
				return fromJsonObject(GSON.fromJson(raw, JsonObject.class));
			} catch (RuntimeException err) {
				LOGGER.warn("bridge.json is malformed ({}); using bundled defaults", err.toString());
			}
		}
		return loadDefaults();
	}

	/** Loads the bundled defaults resource; null when it cannot be read. */
	public static BridgeConfig loadDefaults() {
		String raw = readResourceOrNull(DEFAULTS_RESOURCE);
		if (raw == null) {
			return null;
		}
		try {
			return fromJsonObject(GSON.fromJson(raw, JsonObject.class));
		} catch (RuntimeException err) {
			LOGGER.error("bundled defaults resource is malformed: {}", err.toString());
			return null;
		}
	}

	public static BridgeConfig fromJsonObject(JsonObject json) {
		return new BridgeConfig(
				requiredString(json, "middlewareBase"),
				requiredBoolean(json, "publicReplies"));
	}

	public JsonObject toJsonObject() {
		JsonObject json = new JsonObject();
		json.addProperty("middlewareBase", middlewareBase);
		json.addProperty("publicReplies", publicReplies);
		return json;
	}

	/** Persists this config to bridge.json (created by /mtriggers set/reset). */
	public void save(Path configPath) throws IOException {
		Files.createDirectories(configPath.getParent());
		Files.writeString(configPath, GSON.toJson(toJsonObject()));
	}

	/**
	 * Returns a new config with one tunable replaced. Throws
	 * IllegalArgumentException for unknown keys or invalid values; the
	 * receiver is never mutated.
	 */
	public BridgeConfig with(String key, String value) {
		switch (key) {
			case "middlewareBase" -> {
				URI uri = parseUri(value);
				if (uri == null || uri.getScheme() == null
						|| !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
					throw new IllegalArgumentException("middlewareBase must be an http(s) URL");
				}
				return new BridgeConfig(value, publicReplies);
			}
			case "publicReplies" -> {
				return new BridgeConfig(middlewareBase, parseBoolean(key, value));
			}
			default -> throw new IllegalArgumentException("unknown key: " + key);
		}
	}

	public String middlewareBase() {
		return middlewareBase;
	}

	public boolean publicReplies() {
		return publicReplies;
	}

	private static String readOrNull(Path path) {
		try {
			return Files.readString(path);
		} catch (IOException err) {
			return null;
		}
	}

	private static String readResourceOrNull(String resource) {
		try (var in = BridgeConfig.class.getResourceAsStream(resource)) {
			if (in == null) {
				return null;
			}
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException err) {
			return null;
		}
	}

	private static String requiredString(JsonObject json, String key) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			throw new JsonParseException("config is missing required key: " + key);
		}
		return json.get(key).getAsString();
	}

	private static boolean requiredBoolean(JsonObject json, String key) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			throw new JsonParseException("config is missing required key: " + key);
		}
		return json.get(key).getAsBoolean();
	}

	private static URI parseUri(String value) {
		try {
			return URI.create(value);
		} catch (IllegalArgumentException err) {
			return null;
		}
	}

	private static boolean parseBoolean(String key, String value) {
		if (value.equalsIgnoreCase("true")) {
			return true;
		}
		if (value.equalsIgnoreCase("false")) {
			return false;
		}
		throw new IllegalArgumentException(key + " must be true or false");
	}
}
