package desk;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs cross-cutting health checks so the GUI can show actionable diagnostics
 * instead of a single red LED. All checks are side-effect free.
 */
public final class Diagnostics {

    private Diagnostics() {}

    public static List<DiagnosticResult> runAll(AppConfig cfg) {
        List<DiagnosticResult> results = new ArrayList<>();
        results.add(checkConfiguration(cfg));
        results.add(checkWorkbook(cfg));
        results.add(checkLogsDirectory(cfg));
        results.add(testWebSocket(cfg));
        results.add(readStatusFile());
        return results;
    }

    public static DiagnosticResult checkConfiguration(AppConfig cfg) {
        boolean ok = cfg != null
                && notBlank(cfg.clientId)
                && notBlank(cfg.authToken)
                && notBlank(cfg.serverWsUrl)
                && notBlank(cfg.excelFilePath);

        StringBuilder detail = new StringBuilder();
        if (cfg == null) {
            detail.append("Configuration could not be loaded.");
        } else {
            append(detail, "Client ID", cfg.clientId);
            append(detail, "Auth Token", mask(cfg.authToken));
            append(detail, "Server WS", cfg.serverWsUrl);
            append(detail, "Excel Path", cfg.excelFilePath);
        }

        if (!ok) {
            detail.append("\n⚠️ Missing required fields. Run the installer or Settings tab.");
        }
        return new DiagnosticResult("Configuration", ok, detail.toString());
    }

    public static DiagnosticResult checkWorkbook(AppConfig cfg) {
        if (cfg == null || !notBlank(cfg.excelFilePath)) {
            return new DiagnosticResult("Workbook", false, "Excel path not configured.");
        }
        Path path = Paths.get(cfg.excelFilePath);
        boolean exists = Files.exists(path);
        boolean readable = Files.isReadable(path);
        boolean writable = Files.isWritable(path);
        StringBuilder detail = new StringBuilder();
        detail.append(path.toAbsolutePath());
        detail.append("\nexists=").append(exists);
        detail.append(", readable=").append(readable);
        detail.append(", writable=").append(writable);

        boolean ok = exists && readable && writable;
        if (!ok) {
            detail.append("\n⚠️ Fix file permissions or choose a different workbook.");
        }
        return new DiagnosticResult("Workbook", ok, detail.toString());
    }

    public static DiagnosticResult checkLogsDirectory(AppConfig cfg) {
        Path dir;
        if (cfg != null && notBlank(cfg.logsDir)) {
            dir = Paths.get(cfg.logsDir.trim());
        } else {
            dir = Paths.get(System.getProperty("user.home"), "apache-bridge", "apachebridge-logs");
        }
        try {
            Files.createDirectories(dir);
            boolean writable = Files.isWritable(dir);
            String detail = dir.toAbsolutePath() + "\nwritable=" + writable;
            return new DiagnosticResult("Logs directory", writable, detail + (writable ? "" : "\n⚠️ Unable to write logs."));
        } catch (IOException e) {
            return new DiagnosticResult("Logs directory", false,
                    dir.toAbsolutePath() + "\nError: " + e.getMessage());
        }
    }

    public static DiagnosticResult testWebSocket(AppConfig cfg) {
        if (cfg == null || !notBlank(cfg.serverWsUrl)) {
            return new DiagnosticResult("Server connectivity", false, "Server URL not configured.");
        }
        URI probe;
        try {
            probe = deriveProbeUri(cfg.serverWsUrl.trim());
        } catch (URISyntaxException e) {
            return new DiagnosticResult("Server connectivity", false, "Invalid URL: " + e.getMessage());
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder(probe)
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "ApacheBridgeDesktop/diagnostics")
                .GET()
                .build();

        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            boolean ok = code >= 200 && code < 400;
            String detail = "HTTP " + code + " from " + probe;
            if (!ok) detail += "\n⚠️ Unexpected status. Server may be unreachable.";
            return new DiagnosticResult("Server connectivity", ok, detail);
        } catch (HttpTimeoutException e) {
            return new DiagnosticResult("Server connectivity", false, "Timed out contacting " + probe);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DiagnosticResult("Server connectivity", false, "Interrupted: " + e.getMessage());
        } catch (IOException e) {
            return new DiagnosticResult("Server connectivity", false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static DiagnosticResult readStatusFile() {
        StatusReporter.Status st = StatusReporter.read();
        if (st == null) {
            return new DiagnosticResult("Runtime status", false,
                    "No status file yet. The desktop client has not reported in.");
        }
        boolean ok = st.connected;
        StringBuilder detail = new StringBuilder();
        detail.append("connected=").append(st.connected);
        detail.append(", lastError=").append(st.lastError == null ? "" : st.lastError);
        long age = Math.max(0, Instant.now().getEpochSecond() - st.epochTs);
        detail.append("\nlast heartbeat ").append(age).append("s ago");
        if (!ok) {
            detail.append("\n⚠️ Investigate logs below.");
        }
        return new DiagnosticResult("Runtime status", ok, detail.toString());
    }

    public static String formatResults(List<DiagnosticResult> results) {
        StringBuilder sb = new StringBuilder();
        for (DiagnosticResult r : results) {
            sb.append(r.ok ? "✅ " : "❌ ");
            sb.append(r.name).append('\n');
            if (r.detail != null && !r.detail.isBlank()) {
                sb.append("    ").append(r.detail.replace("\n", "\n    ")).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(label).append(": ").append(value == null ? "(unset)" : value);
    }

    private static String mask(String token) {
        if (!notBlank(token)) return "(unset)";
        if (token.length() <= 4) return "****";
        return token.substring(0, 2) + "****" + token.substring(token.length() - 2);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static URI deriveProbeUri(String wsUrl) throws URISyntaxException {
        URI uri = new URI(wsUrl);
        String scheme = uri.getScheme();
        if (scheme == null) throw new URISyntaxException(wsUrl, "Missing scheme");
        String replacement;
        switch (scheme.toLowerCase()) {
            case "wss": replacement = "https"; break;
            case "ws":  replacement = "http"; break;
            default:      replacement = scheme; break;
        }
        if (replacement.equalsIgnoreCase(scheme)) {
            return uri;
        }
        return new URI(replacement, uri.getUserInfo(), uri.getHost(), uri.getPort(),
                uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath(),
                uri.getQuery(), uri.getFragment());
    }

    public static final class DiagnosticResult {
        public final String name;
        public final boolean ok;
        public final String detail;

        public DiagnosticResult(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }
    }
}
