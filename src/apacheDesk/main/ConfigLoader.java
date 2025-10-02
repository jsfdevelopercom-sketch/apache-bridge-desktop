package main;


import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

import main.*;

public class ConfigLoader {

    public static AppConfig load() {
        Properties p = new Properties();

        // 1) Prefer local file in working dir (since we’re flat-in-main)
        try (InputStream fis = new FileInputStream("application.properties")) {
            p.load(fis);
        } catch (Exception ignore) {
            // 2) Fallback to classpath (we also include it as a resource)
            try (InputStream in = ConfigLoader.class.getClassLoader()
                    .getResourceAsStream("application.properties")) {
                if (in == null) {
                    throw new IllegalStateException("Missing application.properties (not found on disk or classpath)");
                }
                p.load(in);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load application.properties", e);
            }
        }

        String ws = req(p, "server.ws.url");
        String cid = req(p, "client.id");
        String tok = req(p, "client.authToken");
        String xls = req(p, "excel.file.path");
        String sumCell = def(p, "excel.resultSummary.cell", "A1");
        boolean backup = Boolean.parseBoolean(def(p, "excel.safeWrite.backup", "true"));
        String naming = def(p, "excel.sheet.naming", "PATIENTNAME_DATE");

        long initBackoff = Long.parseLong(def(p, "net.initialReconnectMillis","2000"));
        long maxBackoff = Long.parseLong(def(p, "net.maxReconnectMillis","30000"));

        return new AppConfig(ws, cid, tok, xls, sumCell, backup, naming, initBackoff, maxBackoff);
    }

    private static String req(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + k);
        }
        return v.trim();
    }

    private static String def(Properties p, String k, String dflt) {
        String v = p.getProperty(k);
        return v == null ? dflt : v.trim();
    }
}
