package desk;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DesktopWebSocketClient {

    private final AppConfig cfg;
    private final ExcelUpdater updater;
    private final ObjectMapper mapper = new ObjectMapper();

    // NOTE: non-daemon so JVM stays alive when running headless
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "ws-desktop-scheduler");
                t.setDaemon(false); // CHANGED: was true
                return t;
            });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private volatile WebSocket webSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private volatile long backoffMillis;
    private final Random jitter = new Random();

    // NEW: ensure we don't stack multiple heartbeat tasks after reconnects
    private volatile ScheduledFuture<?> heartbeatTask;

    public DesktopWebSocketClient(AppConfig cfg, ExcelUpdater upd) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.updater = upd;
        this.backoffMillis = Math.max(500, cfg.initialReconnectMillis > 0 ? cfg.initialReconnectMillis : 2000);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        log("Desktop WS: start()");
        StatusReporter.setDisconnected("(starting)");
        scheduleConnect(0);
    }

    public void stop() {
        running.set(false);
        try { scheduler.shutdownNow(); } catch (Exception ignore) {}
        cancelHeartbeat();
        closeSilently(webSocket);
        webSocket = null;
        StatusReporter.setDisconnected("stopped");
        log("Desktop WS: stopped");
    }

    private void scheduleConnect(long delayMs) {
        if (!running.get()) return;
        if (!connecting.compareAndSet(false, true)) return;
        scheduler.schedule(this::doConnect, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private void doConnect() {
        if (!running.get()) { connecting.set(false); return; }
        try {
            final String wsUrl = buildDesktopUrl(cfg);
            log("Connecting to " + wsUrl);
            StatusReporter.setDisconnected("connecting…");

            CompletableFuture<WebSocket> cf = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(wsUrl), new ListenerImpl());

            cf.whenComplete((ws, err) -> {
                connecting.set(false);
                if (err != null) {
                    String msg = rootMsg(err);
                    log("Connect failed: " + msg);
                    StatusReporter.setDisconnected("connect failed: " + msg);
                    scheduleReconnect();
                } else {
                    webSocket = ws;
                    backoffMillis = Math.max(500, cfg.initialReconnectMillis > 0 ? cfg.initialReconnectMillis : 2000);
                    log("Connected.");
                    StatusReporter.setConnected();
                    scheduleHeartbeat(); // will cancel any prior heartbeat
                }
            });
        } catch (Exception ex) {
            connecting.set(false);
            String msg = rootMsg(ex);
            log("Connect exception: " + msg);
            StatusReporter.setDisconnected("connect exception: " + msg);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        long jitterMs = jitter.nextInt(300);
        long delay = Math.min(backoffMillis + jitterMs,
                Math.max(backoffMillis, cfg.maxReconnectMillis > 0 ? cfg.maxReconnectMillis : 30000));
        log("Reconnecting in " + delay + " ms");
        backoffMillis = Math.min(backoffMillis * 2, (cfg.maxReconnectMillis > 0 ? cfg.maxReconnectMillis : 30000));
        scheduleConnect(delay);
    }

    private void scheduleHeartbeat() {
        cancelHeartbeat(); // NEW: prevent stacking
        heartbeatTask = scheduler.scheduleWithFixedDelay(() -> {
            WebSocket ws = webSocket;
            if (!running.get() || ws == null) return;
            try {
                ws.sendPing(java.nio.ByteBuffer.wrap(new byte[]{'p'}));
                StatusReporter.touchHealthy();
            } catch (Throwable t) {
                String msg = rootMsg(t);
                log("Ping failed: " + msg);
                StatusReporter.setDisconnected("ping failed: " + msg);
                safeAbortAndReconnect();
            }
        }, 25, 25, TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> h = heartbeatTask;
        if (h != null) {
            try { h.cancel(true); } catch (Exception ignore) {}
            heartbeatTask = null;
        }
    }

    private void safeAbortAndReconnect() {
        cancelHeartbeat();           // NEW: ensure heartbeat stops on error/close
        closeSilently(webSocket);
        webSocket = null;
        scheduleReconnect();
    }

    private static void closeSilently(WebSocket ws) {
        if (ws == null) return;
        try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join(); } catch (Throwable ignore) {}
        try { ws.abort(); } catch (Throwable ignore) {}
    }

    private static String buildDesktopUrl(AppConfig cfg) {
        String base = cfg.serverWsUrl;
        if (base == null || base.isBlank()) throw new IllegalStateException("serverWsUrl not configured");
        String clientId = enc(nullToEmpty(cfg.clientId));
        String auth     = enc(nullToEmpty(cfg.authToken));
        String sep = base.contains("?") ? "&" : "?";
        return base + sep + "clientId=" + clientId + "&auth=" + auth;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String nullToEmpty(String s){ return s == null ? "" : s; }

    private static String rootMsg(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) r = r.getCause();
        return r.getClass().getSimpleName() + ": " + (r.getMessage() == null ? "(no message)" : r.getMessage());
    }

    private  void log(String s) { 
    	
    	try {
			FileWriter fr = new FileWriter(cfg.logsDir+File.separator+"desktopClientLog.txt");
			PrintWriter pr = new PrintWriter(fr);
			pr.println(LocalDateTime.now() + " >>  " + s);
			pr.close();
			fr.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	
    	
    	System.out.println(s);
    	
    
    }

    private final class ListenerImpl implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            log("WS opened.");
            StatusReporter.setConnected();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String msg = partial.toString();
                partial.setLength(0);
                handleMessage(ws, msg);
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log("WS closed: " + statusCode + " / " + reason);
            StatusReporter.setDisconnected("closed: " + statusCode + " " + reason);
            safeAbortAndReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            String msg = rootMsg(error);
            log("WS error: " + msg);
            StatusReporter.setDisconnected("error: " + msg);
            safeAbortAndReconnect();
        }

        private void handleMessage(WebSocket ws, String json) {
            try {
                ApachePayload payload = mapper.readValue(json, ApachePayload.class);
                String sheet = updater.appendPayload(payload);

                ServerAck ack = new ServerAck();
                ack.status = "SUCCESS";
                ack.message = "Saved to sheet '" + sheet + "'";
                ack.clientId = payload.clientId;
                ack.submissionId = payload.submissionId;
                // Optional: include the workbook path for server logs/debug
               // ack.excelFilePath = cfg.excelFilePath;

                String out = mapper.writeValueAsString(ack);
                ws.sendText(out, true);

                StatusReporter.touchHealthy();
            } catch (Exception ex) {
                String msg = rootMsg(ex);
                log("Handle msg failed: " + msg);
                try {
                    ServerAck ack = new ServerAck();
                    ack.status = "ERROR";
                    ack.message = msg;
                    String out = mapper.writeValueAsString(ack);
                    ws.sendText(out, true);
                } catch (Exception ignore) {}
                // keep running; let reconnection logic handle hard failures
                StatusReporter.setDisconnected("write failed: " + msg);
            }
        }
    }
}
