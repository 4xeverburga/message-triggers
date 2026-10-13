package com.messagetriggers.mod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the bridge config store (pure logic, no Minecraft
 * classes). Scenarios:
 *
 *  - a missing/malformed bridge.json falls back to the bundled defaults
 *    resource (standalone installs work out of the box)
 *  - every tunable round-trips through save/load
 *  - in-game edits ("with") validate values and never mutate in place
 */
class BridgeConfigTest {
	@TempDir
	Path tempDir;

	@Test
	void loadsBundledDefaultsWhenFileIsMissing() {
		BridgeConfig config = BridgeConfig.load(tempDir.resolve("bridge.json"));
		assertNotNull(config);
		assertEquals("http://127.0.0.1:3000", config.middlewareBase());
		assertEquals(true, config.publicReplies());
	}

	@Test
	void readsAllValuesFromFile() throws IOException {
		Path file = tempDir.resolve("bridge.json");
		Files.writeString(file, """
				{
					"middlewareBase": "http://127.0.0.1:9999",
					"publicReplies": false
				}
				""");
		BridgeConfig config = BridgeConfig.load(file);
		assertEquals("http://127.0.0.1:9999", config.middlewareBase());
		assertEquals(false, config.publicReplies());
	}

	@Test
	void malformedFileFallsBackToDefaults() throws IOException {
		Path file = tempDir.resolve("bridge.json");
		Files.writeString(file, "not json at all");
		BridgeConfig config = BridgeConfig.load(file);
		assertNotNull(config);
		assertEquals(true, config.publicReplies());
	}

	@Test
	void fileMissingRequiredKeyFallsBackToDefaults() throws IOException {
		Path file = tempDir.resolve("bridge.json");
		Files.writeString(file, "{\"middlewareBase\": \"http://127.0.0.1:3000\"}");
		BridgeConfig config = BridgeConfig.load(file);
		assertNotNull(config);
		// A missing required key discards the whole file → bundled defaults.
		assertEquals("http://127.0.0.1:3000", config.middlewareBase());
		assertEquals(true, config.publicReplies());
	}

	@Test
	void legacyKeysFromOlderConfigsAreIgnored() throws IOException {
		// Configs written by older mod versions carry removed tunables
		// (triggerPhrase/thinkingAck/sentMemory); they must still load.
		Path file = tempDir.resolve("bridge.json");
		Files.writeString(file, """
				{
					"middlewareBase": "http://127.0.0.1:9999",
					"triggerPhrase": "@bot",
					"thinkingAck": "...thinking...",
					"publicReplies": false,
					"sentMemory": 16
				}
				""");
		BridgeConfig config = BridgeConfig.load(file);
		assertNotNull(config);
		assertEquals("http://127.0.0.1:9999", config.middlewareBase());
		assertEquals(false, config.publicReplies());
	}

	@Test
	void saveThenLoadRoundTrips() throws IOException {
		Path file = tempDir.resolve("bridge.json");
		BridgeConfig original = BridgeConfig.load(file)
				.with("publicReplies", "false");
		original.save(file);
		BridgeConfig reloaded = BridgeConfig.load(file);
		assertEquals(false, reloaded.publicReplies());
	}

	@Test
	void toJsonObjectCarriesEveryKey() {
		BridgeConfig config = BridgeConfig.load(tempDir.resolve("bridge.json"));
		String json = config.toJsonObject().toString();
		for (String key : BridgeConfig.keys()) {
			assertTrue(json.contains("\"" + key + "\""), "missing key: " + key);
		}
	}

	@Test
	void withAppliesValidValues() {
		BridgeConfig base = BridgeConfig.load(tempDir.resolve("bridge.json"));
		assertEquals(false, base.with("publicReplies", "false").publicReplies());
		assertEquals("http://localhost:1234", base.with("middlewareBase", "http://localhost:1234").middlewareBase());
		// original is never mutated
		assertEquals(true, base.publicReplies());
	}

	@Test
	void withRejectsInvalidValues() {
		BridgeConfig base = BridgeConfig.load(tempDir.resolve("bridge.json"));
		assertThrows(IllegalArgumentException.class, () -> base.with("unknownKey", "x"));
		assertThrows(IllegalArgumentException.class, () -> base.with("publicReplies", "maybe"));
		assertThrows(IllegalArgumentException.class, () -> base.with("middlewareBase", "ftp://host"));
		assertThrows(IllegalArgumentException.class, () -> base.with("middlewareBase", "not a url"));
	}
}
