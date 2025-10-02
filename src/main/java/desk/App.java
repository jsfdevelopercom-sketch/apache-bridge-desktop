package desk;

import javax.swing.*;
import java.nio.file.Path;

public final class App {
    public static void main(String[] args) throws Exception {
        boolean headless = false;
        for (String a : args) if ("--headless".equalsIgnoreCase(a)) headless = true;

        // Allow watchdog / service scripts to force headless mode via env var so
        // a manifest "java -jar" launch never surprises users with the GUI.
        if (!headless) {
            String forced = System.getenv("APACHE_BRIDGE_FORCE_HEADLESS");
            if (forced != null && !forced.isBlank()) {
                headless = "1".equals(forced.trim())
                        || "true".equalsIgnoreCase(forced.trim())
                        || "yes".equalsIgnoreCase(forced.trim());
            }
        }

        if (headless) {
            System.setProperty("java.awt.headless", "true");
        }

        // Normal mode (GUI)
        if (!headless) {
            // Just show the control panel; installer is a separate JAR now.
            SwingUtilities.invokeLater(ControlPanel::launchUI);
            return;
        }

        // Headless desktop client (watchdog/autostart will run this)
        AppConfig cfg = ConfigLoader.load();
        if (cfg.serverWsUrl == null || cfg.serverWsUrl.isBlank()
                || cfg.clientId == null || cfg.clientId.isBlank()
                || cfg.authToken == null || cfg.authToken.isBlank()
                || cfg.excelFilePath == null || cfg.excelFilePath.isBlank()) {
            System.err.println("Headless start aborted: config incomplete. Run installer/control panel first.");
            System.exit(2);
        }

        ExcelUpdater updater = new ExcelUpdater(cfg);
        DesktopWebSocketClient ws = new DesktopWebSocketClient(cfg, updater);
        ws.start();

        // keep JVM alive
        Object lock = new Object();
        synchronized (lock) { lock.wait(); }
    }
}
