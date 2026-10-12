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
		assertEquals("@bot", config.triggerPhrase());
		assertEquals("...thinking...", config.thinkingAck());
		assertEquals("Message Triggers failed to answer.", config.failureMessage());
		assertEquals(true, config.publicReplies());
		assertEquals(16, config.sentMemory());
		assertEquals(true, config.wsEnabled());
	}

	@Test
	void readsAllValuesFromFile() throws IOException {
		Path file = tempDir.resolve("bridge.json");
		Files.writeString(file, """
				{
					"middlewareBase": "http://127.0.0.1:9999",
					"triggerPhrase": "@ask",
					"thinkingAck": "one sec",
					"failureMessage": "no answer",
					"publicReplies": false,
					"sentMemory": 32
				}
				""");
		BridgeConfig config = BridgeConfig.load(file);
		assertEquals("http://127.0.0.1:9999", config.middlewareBase());
		assertEquals("@ask", config.triggerPhrase());
		assertEquals("one sec", config.thinkingAck());
		assertEquals("no answer", config.failureMessage());
		assertEquals(false, config.publicReplies());
		assertEquals(32, config.sentMemory());
	}

	@Test
	void wsEnabledIsOptionalAndDefaultsToTrue() throws IOException {
		Path file = tempDir.resolve("bridge.json");
		Files.writeString(file, """
				{
					"middlewareBase": "http://127.0.0.1:3000",
					"triggerPhrase": "@ask",
					"thinkingAck": "one sec",
					"failureMessage": "no answer",
					"publicReplies": true,
					"sentMemory": 16
				}
				""");
		// Absent means true: the push channel is the only reply path (v2).
		assertEquals(true, BridgeConfig.load(file).wsEnabled());
		Files.writeString(file, """
				{
					"middlewareBase": "http://127.0.0.1:3000",
					"triggerPhrase": "@ask",
					"thinkingAck": "one sec",
					"failureMessage": "no answer",
					"publicReplies": true,
					"sentMemory": 16,
					"wsEnabled": true
				}
				""");
		assertEquals(true, BridgeConfig.load(file).wsEnabled());
	}

	@Test
	void malformedFileFallsBackToDefaults() throws IOException {
		Path file = tempDir.resolve("bridge.json");
		Files.writeString(file, "not json at all");
		BridgeConfig config = BridgeConfig.load(file);
		assertNotNull(config);
		assertEquals(16, config.sentMemory());
	}

	@Test
	void fileMissingRequiredKeyFallsBackToDefaults() throws IOException {
		Path file = tempDir.resolve("bridge.json");
		Files.writeString(file, "{\"middlewareBase\": \"http://127.0.0.1:3000\"}");
		BridgeConfig config = BridgeConfig.load(file);
		assertNotNull(config);
		assertEquals("@bot", config.triggerPhrase());
	}

	@Test
	void saveThenLoadRoundTrips() throws IOException {
		Path file = tempDir.resolve("bridge.json");
		BridgeConfig original = BridgeConfig.load(file)
				.with("triggerPhrase", "@hey")
				.with("sentMemory", "7")
				.with("publicReplies", "false");
		original.save(file);
		BridgeConfig reloaded = BridgeConfig.load(file);
		assertEquals("@hey", reloaded.triggerPhrase());
		assertEquals(7, reloaded.sentMemory());
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
		assertEquals(8, base.with("sentMemory", "8").sentMemory());
		assertEquals(false, base.with("publicReplies", "false").publicReplies());
		assertEquals("@ask me", base.with("triggerPhrase", "@ask me").triggerPhrase());
		assertEquals("http://localhost:1234", base.with("middlewareBase", "http://localhost:1234").middlewareBase());
		assertEquals(true, base.with("wsEnabled", "true").wsEnabled());
		assertEquals(false, base.with("wsEnabled", "false").wsEnabled());
		// original is never mutated
		assertEquals(16, base.sentMemory());
		assertEquals(true, base.wsEnabled());
	}

	@Test
	void withRejectsInvalidValues() {
		BridgeConfig base = BridgeConfig.load(tempDir.resolve("bridge.json"));
		assertThrows(IllegalArgumentException.class, () -> base.with("unknownKey", "x"));
		assertThrows(IllegalArgumentException.class, () -> base.with("triggerPhrase", ""));
		assertThrows(IllegalArgumentException.class, () -> base.with("triggerPhrase", "   "));
		assertThrows(IllegalArgumentException.class, () -> base.with("sentMemory", "0"));
		assertThrows(IllegalArgumentException.class, () -> base.with("sentMemory", "-3"));
		assertThrows(IllegalArgumentException.class, () -> base.with("sentMemory", "abc"));
		assertThrows(IllegalArgumentException.class, () -> base.with("publicReplies", "maybe"));
		assertThrows(IllegalArgumentException.class, () -> base.with("middlewareBase", "ftp://host"));
		assertThrows(IllegalArgumentException.class, () -> base.with("middlewareBase", "not a url"));
	}
}
