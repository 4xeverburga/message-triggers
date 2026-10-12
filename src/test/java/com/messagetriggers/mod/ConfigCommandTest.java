package com.messagetriggers.mod;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the in-game config command parser (pure logic, no
 * Minecraft classes). The brigadier registration in MessageTriggersMod is
 * thin glue; everything observable lives here:
 *
 *  - /mtriggers (or "show") lists every tunable with its current value
 *  - "set <key> <value...>" validates via BridgeConfig.with and returns the
 *    new config for the caller to persist
 *  - "reset" returns the bundled defaults
 *  - "path" reports where the config file lives
 *  - anything else fails with a usage hint
 */
class ConfigCommandTest {
	@TempDir
	Path tempDir;

	private Path configPath;

	@BeforeEach
	void setUp() {
		// @TempDir is injected before @BeforeEach, not before field initializers.
		configPath = tempDir.resolve("bridge.json");
	}

	@Test
	void emptyArgsBehaveLikeShow() {
		ConfigCommand.Result result = ConfigCommand.handle("", sampleConfig(), configPath);
		assertTrue(result.success());
		assertNull(result.config());
		assertTrue(result.feedback().contains("sentMemory = 16"));
		assertTrue(result.feedback().contains("triggerPhrase = @bot"));
	}

	@Test
	void showListsEveryTunable() {
		ConfigCommand.Result result = ConfigCommand.handle("show", sampleConfig(), configPath);
		assertTrue(result.success());
		assertNull(result.config());
		for (String key : BridgeConfig.keys()) {
			assertTrue(result.feedback().contains(key), "feedback missing key: " + key);
		}
	}

	@Test
	void setAppliesValueAndReturnsNewConfig() {
		ConfigCommand.Result result = ConfigCommand.handle("set sentMemory 8", sampleConfig(), configPath);
		assertTrue(result.success());
		assertNotNull(result.config());
		assertEquals(8, result.config().sentMemory());
		assertTrue(result.feedback().contains("sentMemory"));
	}

	@Test
	void setTriggerPhraseMayContainSpaces() {
		ConfigCommand.Result result = ConfigCommand.handle("set triggerPhrase @hey there", sampleConfig(), configPath);
		assertTrue(result.success());
		assertEquals("@hey there", result.config().triggerPhrase());
	}

	@Test
	void resetReturnsBundledDefaults() {
		BridgeConfig customized = sampleConfig().with("sentMemory", "3");
		ConfigCommand.Result result = ConfigCommand.handle("reset", customized, configPath);
		assertTrue(result.success());
		assertNotNull(result.config());
		assertEquals(16, result.config().sentMemory());
		assertEquals("@bot", result.config().triggerPhrase());
	}

	@Test
	void pathReportsConfigLocation() {
		ConfigCommand.Result result = ConfigCommand.handle("path", sampleConfig(), configPath);
		assertTrue(result.success());
		assertNull(result.config());
		assertEquals(configPath.toString(), result.feedback());
	}

	@Test
	void unknownSubcommandFailsWithUsage() {
		ConfigCommand.Result result = ConfigCommand.handle("explode", sampleConfig(), configPath);
		assertFalse(result.success());
		assertNull(result.config());
		assertTrue(result.feedback().contains("usage"));
	}

	@Test
	void setUnknownKeyFails() {
		ConfigCommand.Result result = ConfigCommand.handle("set nope 1", sampleConfig(), configPath);
		assertFalse(result.success());
		assertNull(result.config());
	}

	@Test
	void setMissingValueFails() {
		ConfigCommand.Result result = ConfigCommand.handle("set triggerPhrase", sampleConfig(), configPath);
		assertFalse(result.success());
		assertNull(result.config());
	}

	@Test
	void setInvalidValueFails() {
		ConfigCommand.Result result = ConfigCommand.handle("set sentMemory 0", sampleConfig(), configPath);
		assertFalse(result.success());
		assertNull(result.config());
	}

	private BridgeConfig sampleConfig() {
		return BridgeConfig.load(configPath);
	}
}
