package desk;

import java.time.LocalDateTime;

/**
 * Headless background runner for desktop bridge.
 * Use this in autostart scripts so GUI doesn't appear on reboot.
 */
public final class ServiceMain {

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        try {
            AppConfig cfg = ConfigLoader.load();
            System.out.println(ts() + " ServiceMain: starting desktop client. clientId=" + cfg.clientId);

            ExcelUpdater excel = new ExcelUpdater(cfg);
            DesktopWebSocketClient client = new DesktopWebSocketClient(cfg, excel);

            Runtime.getRuntime().addShutdownHook(new Thread(client::stop, "shutdown-ws"));
            client.start(); // reconnect loop; blocks until stop()
        } catch (Throwable t) {
            t.printStackTrace();
            System.err.println(ts() + " ServiceMain failed: " + t.getMessage());
            System.exit(1);
        }
    }

    private static String ts() {
        return "[" + LocalDateTime.now() + "]";
    }
}
