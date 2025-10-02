package main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import main.*;


public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            AppConfig cfg = ConfigLoader.load();
            log.info("ApacheBridge Desktop Client starting. clientId={}", cfg.getClientId());
            DesktopWebSocketClient client = new DesktopWebSocketClient(cfg);
            client.connectAndRun();
        } catch (Exception e) {
            log.error("Fatal error starting Desktop Client", e);
            System.exit(1);
        }
    }
}
