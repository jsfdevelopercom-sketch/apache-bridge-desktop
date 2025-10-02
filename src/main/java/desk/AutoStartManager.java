package desk;

import java.nio.file.*;
import java.util.Locale;

/**
 * Installs OS-specific autostart entries that invoke the single watchdog script
 * written by WatchdogWriter. The watchdog runs the true headless entrypoint
 * (desk.ServiceMain) via classpath and enforces headless.
 *
 * Public API preserved:
 *  - enable(): idempotent install of autostart + watchdog script
 *  - disable(): idempotent removal of autostart artifacts and watchdog script
 *  - startNow(): run the watchdog immediately without reboot
 */
public final class AutoStartManager {

    private AutoStartManager() {}

    /* =======================
       Public API (used by GUI)
       ======================= */

    /** Enable autostart + watchdog on this OS (idempotent). */
    public static void enable() throws Exception {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            installWindowsWatchdog();
        } else if (os.contains("mac")) {
            installMacWatchdog();
        } else {
            installLinuxWatchdog();
        }
    }

    /** Disable autostart + watchdog on this OS (idempotent). */
    public static void disable() throws Exception {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            disableWindowsWatchdog();
        } else if (os.contains("mac")) {
            disableMacWatchdog();
        } else {
            disableLinuxWatchdog();
        }
    }

    /** Immediately launches the watchdog script once, without waiting for reboot. */
    public static void startNow() {
        try {
            final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            final Path baseDir = configDir();
            final Path script = os.contains("win")
                    ? baseDir.resolve("apachebridge-watchdog.ps1")
                    : baseDir.resolve("apachebridge-watchdog.sh");

            JarLocator.trySetExecutable(script);

            ProcessBuilder pb;
            if (os.contains("win")) {
                // Launch minimized PowerShell to run the ps1
                pb = new ProcessBuilder("powershell",
                        "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString());
            } else {
                // Execute the script directly via bash to avoid shell quoting issues
                pb = new ProcessBuilder("bash", script.toString());
            }
            pb.redirectErrorStream(true);
            pb.start();
        } catch (Exception e) {
            System.err.println("[AutoStartManager] startNow failed: " + e.getMessage());
        }
    }

    /* ======================
       Windows implementation
       ====================== */

    private static void installWindowsWatchdog() throws Exception {
        final Path fatJar  = ensureJar();
        final Path baseDir = configDir();
        final Path logsDir = baseDir.resolve("apachebridge-logs");
        Files.createDirectories(baseDir);
        Files.createDirectories(logsDir);

        // Write/refresh the authoritative watchdog script (ps1 on Windows)
        final Path wd = WatchdogWriter.writeWatchdogScript(baseDir, fatJar, logsDir);

        // Place a small Startup .bat to invoke the ps1 at login
        final Path startup = Paths.get(System.getenv("APPDATA"),
                "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
        Files.createDirectories(startup);
        final Path startupBat = startup.resolve("apache-bridge-watchdog-launcher.bat");
        final String bat = "@echo off\r\n"
                + "powershell -NoProfile -ExecutionPolicy Bypass -File \"" + wd.toString() + "\"\r\n";
        Files.writeString(startupBat, bat);
        JarLocator.trySetExecutable(startupBat);
    }

    private static void disableWindowsWatchdog() throws Exception {
        final Path startup = Paths.get(System.getenv("APPDATA"),
                "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
        safeDelete(startup.resolve("apache-bridge-watchdog-launcher.bat"));

        final Path baseDir = configDir();
        safeDelete(baseDir.resolve("apachebridge-watchdog.ps1"));
        // keep logs
    }

    /* ====================
       Linux implementation
       ==================== */

    private static void installLinuxWatchdog() throws Exception {
        final Path fatJar  = ensureJar();
        final Path baseDir = configDir();
        final Path logsDir = baseDir.resolve("apachebridge-logs");
        Files.createDirectories(baseDir);
        Files.createDirectories(logsDir);

        // Write/refresh watchdog script (sh on Linux)
        final Path wd = WatchdogWriter.writeWatchdogScript(baseDir, fatJar, logsDir);

        // Autostart entry
        final Path autostartDir = Paths.get(System.getProperty("user.home"), ".config", "autostart");
        Files.createDirectories(autostartDir);
        final Path desktopFile = autostartDir.resolve("apache-bridge-watchdog.desktop");

        final String desktop =
                "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=APACHE Bridge Watchdog\n" +
                "Comment=Keeps the APACHE Bridge desktop client running\n" +
                "Exec=bash -lc '" + escapeSingleQuotes(wd.toString()) + "'\n" +
                "Terminal=false\n" +
                "X-GNOME-Autostart-enabled=true\n";

        Files.writeString(desktopFile, desktop);
        JarLocator.trySetExecutable(desktopFile);
    }

    private static void disableLinuxWatchdog() throws Exception {
        final Path autostartDir = Paths.get(System.getProperty("user.home"), ".config", "autostart");
        safeDelete(autostartDir.resolve("apache-bridge-watchdog.desktop"));

        final Path baseDir = configDir();
        safeDelete(baseDir.resolve("apachebridge-watchdog.sh"));
        // keep logs
    }

    /* ====================
       macOS implementation
       ==================== */

    private static void installMacWatchdog() throws Exception {
        final Path fatJar  = ensureJar();
        final Path baseDir = configDir(); // keep consistent with Installer
        final Path logsDir = baseDir.resolve("apachebridge-logs");
        Files.createDirectories(baseDir);
        Files.createDirectories(logsDir);

        // Write/refresh watchdog script (sh on macOS too)
        final Path wd = WatchdogWriter.writeWatchdogScript(baseDir, fatJar, logsDir);

        // LaunchAgent plist that runs the script
        final Path launchAgents = Paths.get(System.getProperty("user.home"), "Library", "LaunchAgents");
        Files.createDirectories(launchAgents);
        final Path plist = launchAgents.resolve("com.jsfdev.apachebridge.watchdog.plist");

        final String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
                "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "  <dict>\n" +
                "    <key>Label</key>\n" +
                "    <string>com.jsfdev.apachebridge.watchdog</string>\n" +
                "    <key>ProgramArguments</key>\n" +
                "    <array>\n" +
                "      <string>/bin/bash</string>\n" +
                "      <string>" + wd.toString() + "</string>\n" +
                "    </array>\n" +
                "    <key>RunAtLoad</key>\n" +
                "    <true/>\n" +
                "    <key>KeepAlive</key>\n" +
                "    <true/>\n" +
                "    <key>StandardOutPath</key>\n" +
                "    <string>" + logsDir.resolve("watchdog.log") + "</string>\n" +
                "    <key>StandardErrorPath</key>\n" +
                "    <string>" + logsDir.resolve("watchdog.log") + "</string>\n" +
                "  </dict>\n" +
                "</plist>\n";

        Files.writeString(plist, xml);

        try {
            new ProcessBuilder("launchctl", "load", plist.toString())
                    .inheritIO()
                    .start()
                    .waitFor();
        } catch (Exception ignore) {}
    }

    private static void disableMacWatchdog() throws Exception {
        final Path plist = Paths.get(System.getProperty("user.home"),
                "Library", "LaunchAgents", "com.jsfdev.apachebridge.watchdog.plist");
        try {
            new ProcessBuilder("launchctl", "unload", plist.toString())
                    .inheritIO()
                    .start()
                    .waitFor();
        } catch (Exception ignore) {}

        final Path baseDir = configDir();
        safeDelete(baseDir.resolve("apachebridge-watchdog.sh"));
        safeDelete(plist);
        // keep logs
    }

    /* ===========
       Utilities
       =========== */

    /** Locate the shaded fat jar for classpath use by the watchdog. */
    private static Path ensureJar() {
        Path jar = JarLocator.locateSelfJar();
        if (jar == null) {
            Path guess = Paths.get(System.getProperty("user.dir", "."),
                    "target", "apache-bridge-desktop-all.jar");
            if (Files.isRegularFile(guess)) return guess.toAbsolutePath().normalize();
            throw new IllegalStateException("Could not locate the desktop JAR; build the shaded jar first.");
        }
        return jar;
    }

    /** Unified config dir (match Installer): ~/apache-bridge */
    private static Path configDir() {
        return Paths.get(System.getProperty("user.home"), "apache-bridge");
    }

    private static void safeDelete(Path p) {
        try { if (p != null) Files.deleteIfExists(p); } catch (Exception ignore) {}
    }

    private static String escapeSingleQuotes(String s) {
        return s == null ? "" : s.replace("'", "'\"'\"'");
    }
}
