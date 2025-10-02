package desk;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

/**
 * Creates a desktop "shortcut" to launch the GUI (Control Panel).
 * - Windows: creates a .bat launcher on Desktop (works without COM/PowerShell perms).
 * - macOS:   creates a .command shell script on Desktop (double-clickable).
 * - Linux:   creates a .desktop file on Desktop (freedesktop.org).
 */
public final class ShortcutMaker {

    private ShortcutMaker() {}

    /**
     * Create a Desktop launcher that executes the given command.
     * @param friendlyName  Display name of the shortcut/file (no extension)
     * @param command       Exact command to run (e.g., "java -jar \"C:\path\app.jar\"")
     * @param description   One-line description (if supported)
     */
    public static void createDesktopShortcut(String friendlyName, String command, String description) throws Exception {
        Path desktop = JarLocator.userDesktop();
        Files.createDirectories(desktop);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            createWindowsBat(desktop, friendlyName, command);
        } else if (os.contains("mac")) {
            createMacCommand(desktop, friendlyName, command);
        } else {
            createLinuxDesktopFile(desktop, friendlyName, command, description);
        }
    }

    // ---------- Windows ----------
    private static void createWindowsBat(Path desktop, String name, String command) throws Exception {
        // .bat that starts java with a background window (use 'start "" cmd')
        Path bat = desktop.resolve(sanitize(name) + ".bat");
        // Ensure the working directory is the jar location so relative paths (config) resolve nicely
        Path jar = JarLocator.locateSelfJar();
        Path workDir = jar != null ? jar.getParent() : Paths.get(System.getProperty("user.dir", "."));

        String content =
                "@echo off\r\n" +
                "cd /d \"" + workDir.toString() + "\"\r\n" +
                "start \"APACHE Bridge Control Panel\" " + command + "\r\n";
        Files.writeString(bat, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        JarLocator.trySetExecutable(bat);
    }

    // ---------- macOS ----------
    private static void createMacCommand(Path desktop, String name, String command) throws Exception {
        Path cmd = desktop.resolve(sanitize(name) + ".command");
        Path jar = JarLocator.locateSelfJar();
        Path workDir = jar != null ? jar.getParent() : Paths.get(System.getProperty("user.dir", "."));

        String content = "#!/bin/bash\n" +
                "cd \"" + workDir.toString() + "\"\n" +
                command + "\n";
        Files.writeString(cmd, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        JarLocator.trySetExecutable(cmd);
    }

    // ---------- Linux ----------
    private static void createLinuxDesktopFile(Path desktop, String name, String command, String description) throws Exception {
        Path desktopFile = desktop.resolve(sanitize(name) + ".desktop");
        Path jar = JarLocator.locateSelfJar();
        Path workDir = jar != null ? jar.getParent() : Paths.get(System.getProperty("user.dir", "."));

        // Use bash -lc to ensure PATH/java resolution behaves like a terminal
        String content = """
                [Desktop Entry]
                Type=Application
                Name=%s
                Comment=%s
                Exec=bash -lc 'cd "%s" && %s'
                Terminal=false
                Categories=Utility;
                """.formatted(name, description == null ? "" : description, workDir.toString(), command);

        Files.writeString(desktopFile, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        JarLocator.trySetExecutable(desktopFile);
    }

    private static String sanitize(String s) {
        if (s == null || s.isBlank()) return "ApacheBridge";
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
