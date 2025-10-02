package desk;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.Properties;

/**
 * One-time setup + config writer.
 *
 * - Uses ConfigLoader.load() -> AppConfig
 * - Writes config to ~/.apache-bridge/application.properties
 * - Ensures backup/logs/latest dirs (next to workbook when available; else ~/.apache-bridge)
 * - Optionally creates a new empty ICU workbook if a path was provided but file missing
 * - Drops a Desktop shortcut to the GUI jar (NOT the installer, NOT the headless client)
 * - Writes watchdog script that keeps the headless ServiceMain alive
 * - Writes an "installed flag" at ~/.apache-bridge/installed.flag
 */
public final class Installer {

    // Set by ensureAppFolders(); reused for logs & watchdog placement
    static Path logsPath;

    private Installer() {}

    /**
     * Backwards-compatible entry used by your ControlPanel.
     * - If excelPath is null/blank, we still install; folders are placed under ~/.apache-bridge
     * - If excelPath points to a non-existent file, we create a new empty .xlsx safely
     * - Does NOT auto-start watchdog. ControlPanel calls AutoStartManager.enable() itself when needed.
     */
    public static void performInstall(String excelPath) throws Exception {
        performInstall(excelPath, /*autoStart=*/false);
    }

    /**
     * Same as above but allows the caller to request immediate autostart (watchdog spawn).
     */
    public static void performInstall(String excelPath, boolean autoStartWatchdog) throws Exception {
        AppConfig cfg = ConfigLoader.load();

        // Capture Excel path if provided
        if (excelPath != null && !excelPath.isBlank()) {
            cfg.excelFilePath = excelPath.trim();
        }

        // Ensure folder structure (prefers location near workbook, else ~/.apache-bridge)
        ensureAppFolders(cfg);

        // If user gave an Excel target but file doesn't exist, create a clean workbook.
        if (cfg.excelFilePath != null && !cfg.excelFilePath.isBlank()) {
            Path xlsx = Paths.get(cfg.excelFilePath);
            if (!Files.exists(xlsx)) {
                try {
                    createEmptyWorkbook(xlsx);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Fill safe defaults if missing
        if (isBlank(cfg.clientId))  cfg.clientId  = "NURSING-STATION-01";
        if (isBlank(cfg.authToken)) cfg.authToken = "SUPER_STRONG_SHARED_TOKEN";
        if (isBlank(cfg.serverWsUrl))
            cfg.serverWsUrl = "wss://apache-bridge-server-production.up.railway.app/ws/desktop";

        // Persist configuration
        saveConfig(cfg);

        // Write installed flag
        writeInstalledFlag();

        // Create Desktop shortcut that launches the GUI jar only
        createGuiDesktopShortcut();

        // --- Write watchdog script that launches the true headless ServiceMain ---
        // Determine base dir (next to workbook if set, else ~/.apache-bridge)
        Path baseDir;
        if (!isBlank(cfg.excelFilePath)) {
            Path x = Paths.get(cfg.excelFilePath);
            baseDir = (x.getParent() != null) ? x.getParent() : configDir();
        } else {
            baseDir = configDir();
        }

        // Figure out the shaded jar to use as classpath
        Path located = JarLocator.locateSelfJar();
        Path workDir = (located != null) ? located.getParent() : Paths.get(System.getProperty("user.dir", "."));
        Path fatJar  = (located != null) ? located : workDir.resolve("apache-bridge-desktop-gui-all.jar");

        // Logs directory (created by ensureAppFolders)
        Path logsDir = (logsPath != null) ? logsPath : baseDir.resolve("apachebridge-logs");

        // Generate/update the watchdog script (Unix: .sh, Windows: .ps1)
        try {
            WatchdogWriter.writeWatchdogScript(baseDir, fatJar, logsDir);
        } catch (Exception e) {
            // Do not fail installation for watchdog issues; user can enable later from Control Panel
            System.err.println("Watchdog script creation failed: " + e.getMessage());
        }

        // Optionally start the watchdog now
        if (autoStartWatchdog) {
            AutoStartManager.startNow();
        }
    }

    /**
     * Update client id / token / OpenAI key via Settings tab.
     * Passing null keeps the existing value.
     */
    public static void writeOrUpdateConfig(String clientId, String authToken, String openAiKeyOrNull) throws Exception {
        AppConfig cfg = ConfigLoader.load();
        if (!isBlank(clientId))  cfg.clientId  = clientId;
        if (!isBlank(authToken)) cfg.authToken = authToken;
        if (openAiKeyOrNull != null) cfg.openAiKey = openAiKeyOrNull;

        ensureAppFolders(cfg);
        saveConfig(cfg);
    }

    // ----------------------------------------------------
    // helpers
    // ----------------------------------------------------

    private static void ensureAppFolders(AppConfig cfg) throws Exception {
        // Always ensure config dir exists
        Files.createDirectories(configDir());

        Path base;
        if (!isBlank(cfg.excelFilePath)) {
            Path x = Paths.get(cfg.excelFilePath);
            base = x.getParent() != null ? x.getParent() : configDir();
        } else {
            base = configDir();
        }

        // Create logical subfolders near the workbook (or config dir)
        Files.createDirectories(base.resolve("apachebridge-backups"));
        Files.createDirectories(base.resolve("apachebridge-logs"));
        Files.createDirectories(base.resolve("apachebridge-latest"));

        logsPath = base.resolve("apachebridge-logs");
    }

    private static void saveConfig(AppConfig cfg) throws Exception {
        Properties p = new Properties();

        put(p, "excel.file.path",           cfg.excelFilePath);
        System.out.println("wrote excel file path= " + cfg.excelFilePath);
        put(p, "client.id",                 cfg.clientId);
        put(p, "auth.token",                cfg.authToken);
        put(p, "server.ws.url",             cfg.serverWsUrl);

        // Keep existing config keys you already use:
        put(p, "excel.result.cell",         cfg.excelResultSummaryCell);
        p.setProperty("excel.backup",       String.valueOf(cfg.excelBackup));
        put(p, "sheet.naming",              cfg.sheetNaming);
        p.setProperty("reconnect.initial.ms", String.valueOf(cfg.initialReconnectMillis));
        p.setProperty("reconnect.max.ms",     String.valueOf(cfg.maxReconnectMillis));

        if (cfg.openAiKey != null) {
            p.setProperty("openai.api.key", cfg.openAiKey);
        }
        if (cfg.logsDir == null || cfg.logsDir.trim().isEmpty()) cfg.logsDir = logsPath.toString();
        put(p, "logs.dir", cfg.logsDir);

        Path cfgPath = configPath();
        System.out.println("Writing to property file :"+ cfgPath.toAbsolutePath());
        try (OutputStream out = Files.newOutputStream(cfgPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            p.store(out, "Apache Bridge Desktop Configuration");
        }
    }

    private static void createGuiDesktopShortcut() throws Exception {
        // Use java -jar <GUI JAR> – keep only GUI here
        Path jar = JarLocator.locateSelfJar();
        Path workDir = jar != null ? jar.getParent() : Paths.get(System.getProperty("user.dir", "."));
        Path guiJar = workDir.resolve("apache-bridge-desktop-gui-all.jar");

        String cmd = win()
                ? ("\"" + javaCmd() + "\" -jar \"" + guiJar + "\"")
                : (javaCmd() + " -jar \"" + guiJar + "\"");

        ShortcutMaker.createDesktopShortcut(
                "APACHE Bridge Control Panel",
                cmd,
                "Launch the APACHE Bridge Control Panel GUI");
    }

    private static void writeInstalledFlag() {
        try {
            Path flag = configDir().resolve("installed.flag");
            Files.writeString(flag,
                    "installed=" + System.currentTimeMillis() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception ignore) {}
    }

    /** Small helper to create an empty .xlsx safely. */
    private static void createEmptyWorkbook(Path xlsx) throws Exception {
        if (xlsx.getParent() != null) Files.createDirectories(xlsx.getParent());
        try (Workbook wb = new XSSFWorkbook();
             OutputStream os = Files.newOutputStream(xlsx,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            wb.createSheet("Sheet1");
            wb.write(os);
        }
    }

    private static void put(Properties p, String k, String v) {
        if (v != null) p.setProperty(k, v);
    }

    private static Path configDir() {
        return Paths.get(System.getProperty("user.home"), "apache-bridge");
    }

    private static Path configPath() {
        return configDir().resolve("application.properties");
    }

    private static boolean win() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String javaCmd() {
        String home = System.getProperty("java.home");
        if (home != null && !home.isBlank()) {
            Path bin = Paths.get(home, "bin", win() ? "java.exe" : "java");
            if (Files.isRegularFile(bin)) return bin.toString();
        }
        return "java";
    }

    /** Returns true if installer flag exists. */
    public static boolean isInstalled() {
        Path flag = configDir().resolve("installed.flag");
        return Files.exists(flag);
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
