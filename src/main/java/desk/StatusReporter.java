package desk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;

/**
 * Writes a tiny JSON status file at ~/.apache-bridge/status.json
 *   { "connected": true|false, "lastError": "...", "ts": 1695200000 }
 *
 * GUI reads this to show a green/red indicator and last error.
 */
public final class StatusReporter {

    private StatusReporter() {}

    public static void setConnected() {
        write(Map.of(
                "connected", "true",
                "lastError", "",
                "ts", String.valueOf(Instant.now().getEpochSecond())
        ));
    }

    public static void setDisconnected(String err) {
        write(Map.of(
                "connected", "false",
                "lastError", err == null ? "" : err,
                "ts", String.valueOf(Instant.now().getEpochSecond())
        ));
    }

    /** Use for non-error heartbeat updates (keeps ts fresh). */
    public static void touchHealthy() {
        // update ts while preserving connected=true
        Status s = read();
        if (s == null) {
            setConnected();
        } else if (s.connected) {
            write(Map.of(
                    "connected", "true",
                    "lastError", s.lastError == null ? "" : s.lastError,
                    "ts", String.valueOf(Instant.now().getEpochSecond())
            ));
        } else {
            // do nothing; it will be setConnected on next successful event
        }
    }

    public static Status read() {
        Path p = statusPath();
        if (!Files.exists(p)) return null;
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8).trim();
            // very small hand-parse
            boolean conn = json.contains("\"connected\": true");
            String err = extract(json, "lastError");
            String ts  = extract(json, "ts");
            Status s = new Status();
            s.connected = conn;
            s.lastError = err;
            try { s.epochTs = Long.parseLong(ts); } catch (Exception ignore) {}
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(Map<String,String> kv) {
        Path dir = Paths.get(System.getProperty("user.home"), ".apache-bridge");
        Path p = dir.resolve("status.json");
        try {
            Files.createDirectories(dir);
            String json = "{"
                    + "\"connected\": " + ("true".equals(kv.get("connected")) ? "true" : "false") + ","
                    + "\"lastError\": " + quote(kv.get("lastError")) + ","
                    + "\"ts\": " + kv.get("ts")
                    + "}";
            Files.writeString(p, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignore) {}
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\"";
    }

    private static String extract(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return "";
        int c = json.indexOf(':', i);
        if (c < 0) return "";
        int start = c + 1;
        // skip spaces/quotes
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\"')) start++;
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != '\"') end++;
        return json.substring(start, end).replace("\\\"","\"").replace("\\\\","\\");
    }

    public static final class Status {
        public boolean connected;
        public String lastError;
        public long epochTs;
    }

    private static Path statusPath() {
        return Paths.get(System.getProperty("user.home"), ".apache-bridge", "status.json");
    }
}
