package com.fluxer.srv;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.neovisionaries.ws.client.*;
import okhttp3.OkHttpClient;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class FluxerClient {
    private static final String GATEWAY_URL = "wss://gateway.fluxer.app/?v=1&encoding=json";
    private static final Gson GSON = new Gson();

    // Fluxer keeps a session resumable for 60s after a drop; stay a little under that.
    private static final long RESUME_WINDOW_MS = 55_000L;
    private static final long MAX_BACKOFF_SECONDS = 60L;
    // Resumable close code we use when we drop a connection ourselves (1000/1001 end the session)
    private static final int CLOSE_RESUMABLE = 4000;

    private final String token;
    private volatile WebSocket ws;
    private final FluxerRestApi restApi;
    private volatile int sequence = 0;
    private volatile String sessionId;
    private Timer heartbeatTimer;
    private volatile boolean heartbeatAcked = true;
    private FluxerListener listener;
    private final Logger logger;

    // Reconnect state
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "FluxerSRV-Reconnect");
            t.setDaemon(true);
            return t;
        }
    });
    private volatile boolean shuttingDown = false;
    private volatile boolean fatal = false;
    private volatile boolean reconnectScheduled = false;
    private volatile long disconnectedAt = 0L;
    private int reconnectAttempts = 0;

    // Last presence we want to show (re-sent on identify so it survives reconnects)
    private volatile JsonObject presence;
    private volatile boolean ready = false;

    public FluxerClient(String token, Logger logger, OkHttpClient httpClient) {
        this.token = token;
        this.logger = logger;
        this.restApi = new FluxerRestApi(token, httpClient);
    }

    public void setListener(FluxerListener listener) {
        this.listener = listener;
    }

    /** Connects to the gateway. A failed attempt is retried automatically with backoff. */
    public void connect() {
        shuttingDown = false;
        fatal = false;
        openSocket();
    }

    private void openSocket() {
        if (shuttingDown || fatal) {
            return;
        }
        reconnectScheduled = false;
        try {
            WebSocket socket = new WebSocketFactory()
                    .setConnectionTimeout(5000)
                    .createSocket(GATEWAY_URL);
            ws = socket;
            socket.addListener(new WebSocketAdapter() {
                @Override
                public void onTextMessage(WebSocket websocket, String text) throws Exception {
                    if (websocket != ws) {
                        return;
                    }
                    try {
                        handleMessage(text);
                    } catch (Exception e) {
                        logger.warning("[Fluxer] Error handling gateway message: " + e.getMessage());
                    }
                }

                @Override
                public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame,
                        WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
                    if (websocket != ws) {
                        return; // a stale socket we already replaced
                    }
                    int code = serverCloseFrame != null ? serverCloseFrame.getCloseCode()
                            : (clientCloseFrame != null ? clientCloseFrame.getCloseCode() : -1);
                    String reason = serverCloseFrame != null ? serverCloseFrame.getCloseReason() : null;
                    handleDisconnect(code, reason);
                }

                @Override
                public void onError(WebSocket websocket, WebSocketException cause) throws Exception {
                    logger.severe("[Fluxer] Gateway error: " + cause.getMessage());
                }
            });
            socket.connect();
        } catch (Exception e) {
            logger.warning("[Fluxer] Could not connect to gateway: " + e.getMessage());
            ready = false;
            scheduleReconnect();
        }
    }

    private void handleDisconnect(int code, String reason) {
        ready = false;
        stopHeartbeat();
        if (disconnectedAt == 0L) {
            disconnectedAt = System.currentTimeMillis();
        }
        if (shuttingDown) {
            return;
        }

        logger.warning("[Fluxer] Disconnected from gateway"
                + (code > 0 ? " (code " + code + (reason != null ? ": " + reason : "") + ")" : "") + ".");

        // Fluxer ends the session on 1000/1001, and a bad sequence (4007) means our resume
        // state is unusable. Either way the next connection must Identify fresh.
        if (code == 1000 || code == 1001 || code == 4007) {
            sessionId = null;
            sequence = 0;
        }

        // Codes where retrying can't help: bad token, bad shard/version, sharding required
        if (code == 4004 || code == 4010 || code == 4011 || code == 4012) {
            fatal = true;
            logger.severe("[Fluxer] Gateway close code " + code + " is not recoverable. "
                    + "Check your bot token and plugin version, then run /fluxer reload.");
            return;
        }
        scheduleReconnect();
    }

    private synchronized void scheduleReconnect() {
        if (shuttingDown || fatal || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        long delay = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(reconnectAttempts, 6));
        reconnectAttempts++;
        logger.info("[Fluxer] Reconnecting to gateway in " + delay + "s...");
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    openSocket();
                }
            }, delay, TimeUnit.SECONDS);
        } catch (Exception e) {
            reconnectScheduled = false; // scheduler already shut down
        }
    }

    /** Drops the current connection in a way Fluxer lets us resume. */
    private void dropConnection(String reason) {
        WebSocket socket = ws;
        if (socket != null) {
            socket.disconnect(CLOSE_RESUMABLE, reason);
        }
    }

    private void handleMessage(String text) {
        JsonObject packet = GSON.fromJson(text, JsonObject.class);
        if (packet == null || !packet.has("op"))
            return;

        int op = packet.get("op").getAsInt();

        if (packet.has("s") && !packet.get("s").isJsonNull()) {
            sequence = packet.get("s").getAsInt();
        }

        switch (op) {
            case 10: // Hello
                int heartbeatInterval = packet.getAsJsonObject("d").get("heartbeat_interval").getAsInt();
                heartbeatAcked = true;
                startHeartbeat(heartbeatInterval);
                reconnectAttempts = 0;
                if (canResume()) {
                    resume();
                } else {
                    sessionId = null;
                    sequence = 0;
                    identify();
                }
                break;
            case 0: // Dispatch
                String type = packet.get("t").getAsString();
                handleDispatch(type, packet.getAsJsonObject("d"));
                break;
            case 1: // Server asked for a heartbeat right now
                sendHeartbeat();
                break;
            case 7: // Reconnect: server is about to close with 4000; reconnect and resume
                logger.info("[Fluxer] Gateway asked us to reconnect.");
                dropConnection("Reconnect requested");
                break;
            case 9: // Invalid session: must start fresh with Identify
                logger.info("[Fluxer] Session invalidated; starting a new session.");
                sessionId = null;
                sequence = 0;
                ready = false;
                scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (!shuttingDown && ws != null && ws.isOpen()) {
                            identify();
                        }
                    }
                }, 2, TimeUnit.SECONDS);
                break;
            case 11: // Heartbeat ACK
                heartbeatAcked = true;
                break;
        }
    }

    private boolean canResume() {
        return sessionId != null
                && disconnectedAt != 0L
                && System.currentTimeMillis() - disconnectedAt < RESUME_WINDOW_MS;
    }

    private void resume() {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", 6);

        JsonObject d = new JsonObject();
        d.addProperty("token", token.replace("Bot ", ""));
        d.addProperty("session_id", sessionId);
        d.addProperty("seq", sequence);
        payload.add("d", d);

        logger.info("[Fluxer] Resuming gateway session...");
        ws.sendText(payload.toString());
        // Commands sent after Resume are handled after it, so the session is usable straight away
        disconnectedAt = 0L;
        ready = true;
        if (presence != null) {
            sendPresence();
        }
    }

    private void identify() {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", 2);

        JsonObject d = new JsonObject();
        d.addProperty("token", token.replace("Bot ", ""));

        JsonObject properties = new JsonObject();
        properties.addProperty("os", "linux");
        properties.addProperty("browser", "fluxer-srv");
        properties.addProperty("device", "fluxer-srv");

        d.add("properties", properties);
        if (presence != null) {
            d.add("presence", presence);
        }
        payload.add("d", d);

        ws.sendText(payload.toString());
    }

    private void startHeartbeat(int interval) {
        stopHeartbeat();
        heartbeatTimer = new Timer("FluxerSRV-Heartbeat", true);
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (!heartbeatAcked) {
                        // No ACK for the last heartbeat: the connection is dead. Drop it and reconnect.
                        logger.warning("[Fluxer] Gateway heartbeat was not acknowledged; reconnecting.");
                        dropConnection("No heartbeat ACK");
                        return;
                    }
                    sendHeartbeat();
                } catch (Exception e) {
                    logger.warning("[Fluxer] Heartbeat failed: " + e.getMessage());
                }
            }
        }, interval, interval);
    }

    private void sendHeartbeat() {
        JsonObject heartbeat = new JsonObject();
        heartbeat.addProperty("op", 1);
        heartbeat.add("d", sequence == 0 ? null : GSON.toJsonTree(sequence));
        heartbeatAcked = false;
        WebSocket socket = ws;
        if (socket != null && socket.isOpen()) {
            socket.sendText(heartbeat.toString());
        }
    }

    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
    }

    private void handleDispatch(String type, JsonObject data) {
        if (type.equals("READY")) {
            sessionId = data.get("session_id").getAsString();
            disconnectedAt = 0L;
            ready = true;
            if (presence != null) {
                sendPresence();
            }
            logger.info("[Fluxer] Logged in as " + data.getAsJsonObject("user").get("username").getAsString());
        } else if (type.equals("MESSAGE_CREATE")) {
            if (listener != null) {
                listener.onMessageCreate(data);
            }
        }
    }

    /**
     * Sets the bot's Fluxer custom status (gateway opcode 3, "custom_status").
     *
     * @param text      the status text (1-128 characters; longer text is truncated)
     * @param status    online, idle, dnd or invisible
     */
    public void updatePresence(String text, String status) {
        if (text != null && text.length() > 128) {
            text = text.substring(0, 128);
        }

        JsonObject customStatus = new JsonObject();
        customStatus.addProperty("text", text);

        JsonObject p = new JsonObject();
        p.addProperty("status", status);
        p.addProperty("afk", false);
        p.addProperty("mobile", false);
        p.add("custom_status", customStatus);
        presence = p;

        if (ready) {
            sendPresence();
        }
    }

    private void sendPresence() {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("op", 3);
            payload.add("d", presence);
            if (ws != null && ws.isOpen()) {
                ws.sendText(payload.toString());
            }
        } catch (Exception e) {
            logger.warning("[Fluxer] Failed to update presence: " + e.getMessage());
        }
    }

    public FluxerRestApi getRestApi() {
        return restApi;
    }

    public void shutdown() {
        shuttingDown = true;
        ready = false;
        stopHeartbeat();
        scheduler.shutdownNow();
        if (ws != null) {
            ws.disconnect();
        }
    }
}
