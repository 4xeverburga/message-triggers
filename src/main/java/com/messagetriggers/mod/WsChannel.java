package com.messagetriggers.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The middleware↔mod channel — the mod's ONLY network surface (contract
 * v4; java.net.http.WebSocket, JDK built-in, no new dependencies).
 * Connects at {middlewareBase}/api/ws and routes every valid inbound frame
 * to the display sink on the client thread — never calls Minecraft APIs
 * from the WS thread. Pure frame logic lives here too (static,
 * unit-tested):
 *
 *  - inbound: {"text": str, "mode": "local"|"public"|"localStream"}, mode
 *    optional (default local), unknown modes drop the frame
 *  - outbound: {"type": "chat", "sender": str, "message": str}, one per
 *    chat line, fire-and-forget (dropped while disconnected)
 *
 * Semantics:
 *  - auto-reconnect with exponential backoff (1s → 30s cap) that resets on
 *    a successful open, and one connect attempt at a time
 *  - stop() ends the channel for good (the game is shutting down)
 */
public final class WsChannel implements WebSocket.Listener {
	/** How a pushed message is displayed (contract v4 push frames). */
	public enum Mode {
		/** Echo into the local HUD only. */
		LOCAL,
		/** Sent to the server as the player when publicReplies is on. */
		PUBLIC,
		/** Streaming chunk: accumulates into ONE local HUD line, in place. */
		LOCAL_STREAM
	}

	/** Displays a decoded push on the client thread (implementations handle null-bridge). */
	public interface PushSink {
		void accept(Push push);
	}

	/** A decoded push: display text + how to display it. */
	public record Push(String text, Mode mode) {
	}

	/** Backoff ladder: 1, 2, 4, 8, 15, 30 (then 30, 30, ...). */
	private static final List<Long> BACKOFF_SECS = List.of(1L, 2L, 4L, 8L, 15L, 30L);

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private final URI uri;
	private final Logger log;
	private final PushSink sink;

	private final ScheduledExecutorService scheduler =
			Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "messagetriggers-ws");
				t.setDaemon(true);
				return t;
			});

	private final AtomicBoolean connecting = new AtomicBoolean(false);
	private final AtomicBoolean stopped = new AtomicBoolean(false);
	private final AtomicReference<WebSocket> socket = new AtomicReference<>();
	private final AtomicInteger failures = new AtomicInteger(0);
	private static final Gson GSON = new Gson();
	/** Strings accumulated across fragment frames; cleared on the final part. */
	private final StringBuilder partial = new StringBuilder();

	WsChannel(String middlewareBase, Logger log, PushSink sink) {
		this.uri = wsUri(middlewareBase);
		this.log = log;
		this.sink = sink;
	}

	/** ws[s]:// + path of middlewareBase + "/api/ws"; deterministic for tests. */
	static URI wsUri(String middlewareBase) {
		URI base = URI.create(middlewareBase);
		String scheme = "https".equals(base.getScheme()) ? "wss" : "ws";
		String path = base.getPath() == null ? "" : base.getPath();
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return URI.create(scheme + "://" + base.getRawAuthority() + path + "/api/ws");
	}

	/**
	 * Pure frame parser: decodes a text frame into a Push, or null when the
	 * frame is empty/malformed — anything ambiguous is DROPPED, never
	 * partially displayed.
	 */
	static Push parse(String frame) {
		if (frame == null || frame.isEmpty()) {
			return null;
		}
		JsonObject json;
		try {
			json = GSON.fromJson(frame, JsonObject.class);
		} catch (JsonParseException err) {
			return null;
		}
		if (json == null || !json.has("text") || json.get("text").isJsonNull()) {
			return null;
		}
		String text = json.get("text").getAsString();
		if (text == null || text.isBlank()) {
			return null;
		}
		Mode mode;
		if (json.has("mode") && !json.get("mode").isJsonNull()) {
			String value = json.get("mode").getAsString();
			if ("public".equals(value)) {
				mode = Mode.PUBLIC;
			} else if ("local".equals(value)) {
				mode = Mode.LOCAL;
			} else if ("localStream".equals(value)) {
				mode = Mode.LOCAL_STREAM;
			} else {
				return null;
			}
		} else {
			mode = Mode.LOCAL;
		}
		return new Push(text, mode);
	}

	/**
	 * Serializes an outbound chat frame: {"type": "chat", "sender", "message"}.
	 * One frame per chat line; control characters are escaped by Gson.
	 */
	static String buildChatFrame(String sender, String message) {
		JsonObject frame = new JsonObject();
		frame.addProperty("type", "chat");
		frame.addProperty("sender", sender);
		frame.addProperty("message", message);
		return GSON.toJson(frame);
	}

	/**
	 * Forwards a chat line to the middleware, fire-and-forget: when the
	 * socket is not connected the line is dropped and logged (the mod never
	 * queues — see the contract's reliability rules, both directions).
	 * Called on the client thread; sendText is safe from any thread.
	 */
	void sendChat(String sender, String message) {
		WebSocket ws = socket.get();
		if (ws == null) {
			log.debug("ws: chat line dropped (not connected): <{}> {}", sender, message);
			return;
		}
		ws.sendText(buildChatFrame(sender, message), true)
				.whenComplete((unused, err) -> {
					if (err != null) {
						log.info("ws: chat line send failed: {}", rootCause(err));
					}
				});
	}

	void start() {
		connect();
	}

	private void connect() {
		if (stopped.get() || !connecting.compareAndSet(false, true)) {
			return;
		}
		HTTP.newWebSocketBuilder().buildAsync(uri, this).whenComplete((ws, err) -> {
			connecting.set(false);
			if (stopped.get()) {
				return;
			}
			if (err != null) {
				log.info("ws connect to {} failed: {}", uri, rootCause(err));
				scheduleReconnect();
				return;
			}
			log.info("ws push channel connected: {}", uri);
		});
	}

	void stop() {
		stopped.set(true);
		scheduler.shutdownNow();
		WebSocket current = socket.getAndSet(null);
		if (current != null) {
			current.abort();
		}
	}

	// ── WebSocket.Listener ─────────────────────────────────────────────────

	@Override
	public void onOpen(WebSocket ws) {
		socket.set(ws);
		failures.set(0);
		// The JDK listener protocol: request data or nothing ever arrives.
		ws.request(1);
	}

	@Override
	public java.util.concurrent.CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
		partial.append(data);
		if (last) {
			String frame = partial.toString();
			partial.setLength(0);
			Push push = parse(frame);
			if (push != null) {
				// Hop onto the client thread before touching any Minecraft API.
				sink.accept(push);
				log.debug("ws push: mode={} text={}", push.mode(), push.text());
			} else {
				log.info("ws: ignoring malformed push frame ({} bytes)", frame.length());
			}
		}
		ws.request(1);
		return null;
	}

	@Override
	public java.util.concurrent.CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
		socket.set(null);
		if (!stopped.get()) {
			log.info("ws closed by middleware ({} {})", reason, statusCode == 1006 ? "abnormal" : statusCode);
			scheduleReconnect();
		}
		return null;
	}

	@Override
	public void onError(WebSocket ws, Throwable error) {
		socket.set(null);
		log.info("ws error: {}", rootCause(error));
	}

	// ── internals ─────────────────────────────────────────────────────────

	private void scheduleReconnect() {
		if (stopped.get()) {
			return;
		}
		int index = Math.min(failures.get(), BACKOFF_SECS.size() - 1);
		long delay = BACKOFF_SECS.get(index);
		failures.incrementAndGet();
		scheduler.schedule(() -> {
			if (!stopped.get()) {
				connect();
			}
		}, delay, TimeUnit.SECONDS);
	}

	private static String rootCause(Throwable err) {
		while (err.getCause() != null && err.getCause() != err) {
			err = err.getCause();
		}
		return err.toString();
	}
}
