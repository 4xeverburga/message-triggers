package com.messagetriggers.e2e;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * E2E driver mod (test-only, never shipped). Loaded into a real dev client
 * alongside the message-triggers mod by e2e/run-e2e.sh. It records every
 * chat line rendered in the HUD (via ChatRecorderMixin — the SAME injection
 * point the production ChatHudMixin uses, so what it sees is what the bridge
 * sees), then after joining the local vanilla server it injects scripted
 * chat lines and asserts the full round trip:
 *
 *   untagged line  → POST /api/chat → 202 logged (stub request log)
 *   tagged line    → thinking ack in HUD → 200 reply echoed into the HUD
 *   tagged failure → 502 reply echoed into the HUD
 *
 * The verdict is written to <gameDir>/e2e-result.json and the client stops
 * itself; e2e/run-e2e.sh reads the result and greps the client log for the
 * "bridge loaded" line. The stub middleware (e2e/stub-middleware.py) owns
 * the deterministic reply values asserted here.
 */
public final class E2EDriverMod implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("e2edriver");

	// Scenario constants — the stub middleware owns the matching replies.
	static final String SENDER = "E2ETester";
	static final String TRIGGER = "@bot";
	static final String UNTAGGED_LINE = "<" + SENDER + "> hello world";
	static final String TAGGED_LINE = "<" + SENDER + "> " + TRIGGER + " hello";
	static final String FAILURE_LINE = "<" + SENDER + "> " + TRIGGER + " failme please";
	static final String ACK = "...thinking...";
	static final String REPLY = "E2E-REPLY-OK";
	static final String FAILURE_REPLY = "STUB-FAILURE-REPLY";

	private static final int SETTLE_TICKS = 100;
	private static final int GAP_TICKS = 20;
	private static final int REPLY_TIMEOUT_TICKS = 300;
	private static final int JOIN_TIMEOUT_TICKS = 1200;
	private static final int WATCHDOG_TICKS = 2400;

	private static final List<String> hudLines = new CopyOnWriteArrayList<>();
	private static final Gson GSON = new Gson();

	private enum Phase { WAIT_JOIN, SETTLE, SENT_UNTAGGED, WAIT_REPLY, WAIT_FAILURE, DONE }

	private Phase phase = Phase.WAIT_JOIN;
	private int ticksInPhase = 0;
	private int totalTicks = 0;

	/** Called by ChatRecorderMixin for every line rendered in the chat HUD. */
	public static void record(String line) {
		hudLines.add(line);
	}

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				LOGGER.info("e2e: client started — waiting for world join"));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			LOGGER.info("e2e: world joined — starting scenario");
			phase = Phase.SETTLE;
			ticksInPhase = 0;
		});
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (phase != Phase.DONE) {
				writeResult(false, "client stopped before the scenario finished");
			}
		});
	}

	private void tick(MinecraftClient client) {
		if (phase == Phase.DONE) {
			return;
		}
		totalTicks++;
		ticksInPhase++;
		switch (phase) {
			case WAIT_JOIN -> {
				if (ticksInPhase >= JOIN_TIMEOUT_TICKS) {
					finish(client, false, "never joined a world (quickplay failed?)");
				}
			}
			case SETTLE -> {
				if (ticksInPhase >= SETTLE_TICKS) {
					inject(UNTAGGED_LINE);
					next(Phase.SENT_UNTAGGED);
				}
			}
			case SENT_UNTAGGED -> {
				if (ticksInPhase >= GAP_TICKS) {
					inject(TAGGED_LINE);
					next(Phase.WAIT_REPLY);
				}
			}
			case WAIT_REPLY -> {
				if (seen(ACK) && seen(REPLY)) {
					inject(FAILURE_LINE);
					next(Phase.WAIT_FAILURE);
				} else if (ticksInPhase >= REPLY_TIMEOUT_TICKS) {
					finish(client, false, "no ack/reply within the timeout");
				}
			}
			case WAIT_FAILURE -> {
				if (seen(FAILURE_REPLY)) {
					// finish() runs the full HUD + middleware verification itself.
					finish(client, true, null);
				} else if (ticksInPhase >= REPLY_TIMEOUT_TICKS) {
					finish(client, false, "no failure reply within the timeout");
				}
			}
			default -> {
			}
		}
		if (phase != Phase.DONE && totalTicks >= WATCHDOG_TICKS) {
			finish(client, false, "watchdog timeout");
		}
	}

	private void next(Phase target) {
		phase = target;
		ticksInPhase = 0;
	}

	private boolean seen(String needle) {
		return hudLines.stream().anyMatch(line -> line.contains(needle));
	}

	/** Injects a chat line through the real HUD entry point (one-arg addMessage delegates to the hooked three-arg overload). */
	private void inject(String line) {
		LOGGER.info("e2e: injecting chat line: {}", line);
		MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(line));
	}

	/** Middleware-side assertions against the stub's request log. */
	private boolean verify(List<Check> checks) {
		Path stubLog = FabricLoader.getInstance().getGameDir().resolve("stub-requests.jsonl");
		String content = readOrNull(stubLog);
		check(checks, content != null, "stub request log exists");
		if (content == null) {
			return false;
		}
		check(checks, content.contains("hello world"), "untagged line reached the middleware");
		check(checks, content.contains(TAGGED_LINE.substring(TAGGED_LINE.indexOf('>') + 2)),
				"tagged line reached the middleware");
		check(checks, content.contains("failme"), "failure line reached the middleware");
		check(checks, content.contains(SENDER), "sender name reached the middleware");
		return checks.stream().allMatch(c -> c.pass);
	}

	private void check(List<Check> checks, boolean pass, String name) {
		checks.add(new Check(name, pass));
		LOGGER.info("e2e: {} {}", pass ? "PASS" : "FAIL", name);
	}

	private void finish(MinecraftClient client, boolean pass, String error) {
		List<Check> checks = new ArrayList<>();
		check(checks, seen(ACK), "thinking ack appeared in the HUD");
		check(checks, seen(REPLY), "agent reply echoed into the HUD");
		check(checks, seen(FAILURE_REPLY), "failure reply echoed into the HUD");
		boolean middlewareOk = verify(checks);
		boolean result = pass && middlewareOk;
		writeResult(result, error);
		LOGGER.info("e2e: scenario finished — {}", result ? "PASS" : "FAIL: " + error);
		phase = Phase.DONE;
		client.scheduleStop();
	}

	private void writeResult(boolean pass, String error) {
		JsonObject result = new JsonObject();
		result.addProperty("pass", pass);
		if (error != null) {
			result.addProperty("error", error);
		}
		result.addProperty("hudLineCount", hudLines.size());
		try {
			Path file = FabricLoader.getInstance().getGameDir().resolve("e2e-result.json");
			Files.writeString(file, GSON.toJson(result), StandardCharsets.UTF_8);
		} catch (IOException err) {
			LOGGER.error("e2e: could not write the result file: {}", err.toString());
		}
	}

	private static String readOrNull(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException err) {
			return null;
		}
	}

	private record Check(String name, boolean pass) {
	}
}
