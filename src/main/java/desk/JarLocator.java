package desk;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Resolves the absolute path of the running fat JAR.
 * Falls back gracefully when running from IDE/classes.
 */
public final class JarLocator {

    private JarLocator() {}

    /**
     * @return Path to the running JAR if available; otherwise best-effort guess of the fat jar
     *         under ./target (apache-bridge-desktop-all.jar). Returns null if nothing sensible found.
     */
    public static Path locateSelfJar() {
        try {
            // Try code source (works when running from the jar)
            URI uri = JarLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String raw = URLDecoder.decode(uri.getPath(), StandardCharsets.UTF_8);
            Path p = Paths.get(raw);
            if (Files.isRegularFile(p) && raw.toLowerCase().endsWith(".jar")) {
                return p.toAbsolutePath().normalize();
            }
        } catch (Exception ignore) {}

        // If running from classes (IDE), try to find the shaded jar in target/
        try {
            Path projectDir = guessProjectRoot();
            if (projectDir != null) {
                Path target = projectDir.resolve("target");
                if (Files.isDirectory(target)) {
                    // Prefer the shaded "all" jar
                    Path shaded = target.resolve("apache-bridge-desktop-all.jar");
                    if (Files.isRegularFile(shaded)) return shaded.toAbsolutePath().normalize();

                    // Otherwise, pick the newest JAR in target
                    try {
                        Path newest = Files.list(target)
                                .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".jar"))
                                .max((a,b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                                .orElse(null);
                        if (newest != null) return newest.toAbsolutePath().normalize();
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {}

        return null;
    }

    /** Try to infer the project root when launched from IDE/classes. */
    private static Path guessProjectRoot() {
        try {
            // user.dir points to the working dir; often the project root when launched from IDE
            Path wd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            if (Files.exists(wd.resolve("pom.xml"))) return wd;

            // If executed from target/classes, step up twice
            Path maybeTarget = wd.resolve("target");
            if (Files.isDirectory(maybeTarget)) {
                Path parent = wd.getParent();
                if (parent != null && Files.exists(parent.resolve("pom.xml"))) return parent;
            }

            // Look upwards up to 5 levels for a pom.xml
            Path cur = wd;
            for (int i = 0; i < 5 && cur != null; i++) {
                if (Files.exists(cur.resolve("pom.xml"))) return cur;
                cur = cur.getParent();
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** Return user's Desktop path cross-platform (best effort). */
    public static Path userDesktop() {
        Path home = Paths.get(System.getProperty("user.home", System.getProperty("userprofile", ".")));
        // Common default
        Path desktop = home.resolve("Desktop");
        if (Files.isDirectory(desktop)) return desktop;

        // Some locales / OSs may differ; fallback to home
        return home;
    }

    /** Make a file executable if the platform supports it. */
    public static void trySetExecutable(Path p) {
        try { p.toFile().setExecutable(true, false); } catch (Exception ignore) {}
        try { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x")); } catch (Exception ignore) {}
    }
}
