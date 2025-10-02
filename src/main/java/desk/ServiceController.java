package desk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Cross-platform helpers to manage the background watchdog + headless service.
 * Exposed to the GUI so operators can recover without touching the terminal.
 */
public final class ServiceController {

    private static final String SERVICE_TOKEN = "desk.ServiceMain";
    private static final String WATCHDOG_TOKEN = "apachebridge-watchdog";

    private ServiceController() {}

    public static ServiceActionResult startService() {
        try {
            AutoStartManager.startNow();
            return new ServiceActionResult(true, "Watchdog launched in the background.");
        } catch (Exception e) {
            return new ServiceActionResult(false, "Failed to launch watchdog: " + e.getMessage());
        }
    }

    public static ServiceActionResult stopService() {
        List<Long> stopped = new ArrayList<>();
        List<Long> failed = new ArrayList<>();

        ProcessHandle.allProcesses().forEach(ph -> {
            if (!ph.isAlive()) return;
            if (matches(ph.info(), SERVICE_TOKEN) || matches(ph.info(), WATCHDOG_TOKEN)) {
                try {
                    boolean terminated = attemptStop(ph);
                    if (terminated) {
                        stopped.add(ph.pid());
                    } else {
                        failed.add(ph.pid());
                    }
                } catch (Exception ex) {
                    failed.add(ph.pid());
                }
            }
        });

        if (stopped.isEmpty() && failed.isEmpty()) {
            return new ServiceActionResult(false, "No running ServiceMain/watchdog processes were found.");
        }

        StringBuilder detail = new StringBuilder();
        if (!stopped.isEmpty()) {
            detail.append("Stopped ").append(stopped.size()).append(" process(es): ").append(stopped).append('.');
        }
        if (!failed.isEmpty()) {
            if (detail.length() > 0) detail.append(' ');
            detail.append("Could not stop ").append(failed.size()).append(" process(es): ").append(failed);
        }
        return new ServiceActionResult(failed.isEmpty(), detail.toString());
    }

    public static ServiceActionResult restartService() {
        ServiceActionResult stop = stopService();
        if (!stop.success) {
            return new ServiceActionResult(false, "Restart aborted – " + stop.message);
        }
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ServiceActionResult start = startService();
        return new ServiceActionResult(start.success, stop.message + "\n" + start.message);
    }

    public static ServiceActionResult enableAutostart() {
        try {
            AutoStartManager.enable();
            return new ServiceActionResult(true, "Autostart entries installed.");
        } catch (Exception e) {
            return new ServiceActionResult(false, "Enable failed: " + e.getMessage());
        }
    }

    public static ServiceActionResult disableAutostart() {
        try {
            AutoStartManager.disable();
            return new ServiceActionResult(true, "Autostart entries removed.");
        } catch (Exception e) {
            return new ServiceActionResult(false, "Disable failed: " + e.getMessage());
        }
    }

    public static String describeAutostart() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path base = baseConfigDir();
        Path script = os.contains("win")
                ? base.resolve("apachebridge-watchdog.ps1")
                : base.resolve("apachebridge-watchdog.sh");
        boolean scriptExists = Files.exists(script);

        boolean autostartExists;
        if (os.contains("win")) {
            Path startup = Paths.get(Optional.ofNullable(System.getenv("APPDATA")).orElse(""),
                    "Microsoft", "Windows", "Start Menu", "Programs", "Startup",
                    "apache-bridge-watchdog-launcher.bat");
            autostartExists = Files.exists(startup);
        } else if (os.contains("mac")) {
            Path plist = Paths.get(System.getProperty("user.home"),
                    "Library", "LaunchAgents", "com.jsfdev.apachebridge.watchdog.plist");
            autostartExists = Files.exists(plist);
        } else {
            Path desktopFile = Paths.get(System.getProperty("user.home"),
                    ".config", "autostart", "apache-bridge-watchdog.desktop");
            autostartExists = Files.exists(desktopFile);
        }

        if (!scriptExists && !autostartExists) {
            return "Not installed";
        }
        if (scriptExists && autostartExists) {
            return "Enabled";
        }
        if (scriptExists) {
            return "Script ready, autostart missing";
        }
        return "Autostart entry exists, script missing";
    }

    public static Path logsDirectory(AppConfig cfg) {
        if (cfg != null && cfg.logsDir != null && !cfg.logsDir.isBlank()) {
            return Paths.get(cfg.logsDir.trim());
        }
        return baseConfigDir().resolve("apachebridge-logs");
    }

    public static Path desktopClientLog(AppConfig cfg) {
        return logsDirectory(cfg).resolve("desktop-client.log");
    }

    public static Path watchdogLog(AppConfig cfg) {
        return logsDirectory(cfg).resolve("watchdog.log");
    }

    public static Path statusFile() {
        return baseConfigDir().resolve("status.json");
    }

    public static Path baseConfigDir() {
        return Paths.get(System.getProperty("user.home"), "apache-bridge");
    }

    private static boolean attemptStop(ProcessHandle handle) throws InterruptedException {
        handle.destroy();
        if (waitForExit(handle, 2)) return true;
        handle.destroyForcibly();
        return waitForExit(handle, 2);
    }

    private static boolean waitForExit(ProcessHandle handle, long seconds) throws InterruptedException {
        try {
            handle.onExit().get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            return !handle.isAlive();
        } catch (ExecutionException e) {
            return !handle.isAlive();
        }
    }

    private static boolean matches(ProcessHandle.Info info, String token) {
        if (info == null) return false;
        if (info.commandLine().map(cmd -> cmd.contains(token)).orElse(false)) return true;
        if (info.command().map(cmd -> cmd.contains(token)).orElse(false)) return true;
        return info.arguments()
                .map(args -> {
                    for (String a : args) {
                        if (a != null && a.contains(token)) return true;
                    }
                    return false;
                })
                .orElse(false);
    }

    public static final class ServiceActionResult {
        public final boolean success;
        public final String message;

        public ServiceActionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
