package main;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import main.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class DesktopWebSocketClient {
    private static final Logger log = LoggerFactory.getLogger(DesktopWebSocketClient.class);

    private final AppConfig cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public DesktopWebSocketClient(AppConfig cfg) {
        this.cfg = cfg;
    }

    public void connectAndRun() {
        long backoff = cfg.getInitialReconnectMillis();
        while (true) {
            try {
                runOnce();
                backoff = cfg.getInitialReconnectMillis();
            } catch (Exception e) {
                log.error("WebSocket loop error: {}", e.toString());
            }
            try {
                log.info("Reconnecting in {} ms ...", backoff);
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, cfg.getMaxReconnectMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runOnce() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create(cfg.getServerWsUrl() + "?clientId=" + cfg.getClientId() + "&auth=" + cfg.getClientAuthToken());
        log.info("Connecting to server: {}", uri);

        ExcelUpdater updater = new ExcelUpdater(cfg);

        http.newWebSocketBuilder()
           .connectTimeout(Duration.ofSeconds(20))
           .buildAsync(uri, new WebSocket.Listener() {
               @Override
               public void onOpen(WebSocket webSocket) {
                   log.info("WebSocket opened");
                   webSocket.request(1);
               }

               @Override
               public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                   String msg = data.toString();
                   log.info("Message received ({} chars)", msg.length());
                   try {
                       ApachePayload payload = mapper.readValue(msg, ApachePayload.class);

                       if (!cfg.getClientId().equals(payload.getClientId()) ||
                           !cfg.getClientAuthToken().equals(payload.getAuthToken())) {
                           log.warn("Client/Auth mismatch; ignoring message");
                           sendAck(webSocket, new ServerAck(cfg.getClientId(),
                                   ServerAck.Status.ERROR,
                                   "Client/Auth mismatch", payload.getUhid(), payload.getPatientName()));
                       } else {
                           updater.appendPayload(payload);
                           sendAck(webSocket, new ServerAck(cfg.getClientId(),
                                   ServerAck.Status.SUCCESS,
                                   "Excel updated", payload.getUhid(), payload.getPatientName()));
                       }
                   } catch (Exception ex) {
                       log.error("Failed processing message", ex);
                       try {
                           ServerAck ack = new ServerAck(cfg.getClientId(), ServerAck.Status.ERROR,
                                   ex.getMessage(), null, null);
                           sendAck(webSocket, ack);
                       } catch (Exception ignore) { }
                   }

                   webSocket.request(1);
                   return null;
               }

               @Override
               public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                   webSocket.request(1);
                   return null;
               }

               @Override
               public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                   log.info("WebSocket closed: {} {}", statusCode, reason);
                   return null;
               }

               @Override
               public void onError(WebSocket webSocket, Throwable error) {
                   log.error("WebSocket error", error);
               }

               private void sendAck(WebSocket ws, ServerAck ack) throws JsonProcessingException {
                   String json = mapper.writeValueAsString(ack);
                   ws.sendText(json, true);
               }
           }).join();

        while (!Thread.currentThread().isInterrupted()) {
            TimeUnit.MINUTES.sleep(5);
        }
    }
}
