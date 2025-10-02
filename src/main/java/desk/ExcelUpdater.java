package desk;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Robust Excel writer with:
 *  - crash-safe temp -> atomic replace (with fallback)
 *  - file lock + bounded retries
 *  - single rolling workbook backup kept in "backup/"
 *  - single latest-entry files (JSON + TXT) kept in "backup/"
 *  - monthly text log in "logs/"
 */
public class ExcelUpdater {

    private final AppConfig cfg;

    // formatting
    private static final DateTimeFormatter TS_FILE  = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TS_SHEET = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    private static final DateTimeFormatter TS_LOG   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LOG_MONTH= DateTimeFormatter.ofPattern("yyyy-MM");

    // retry settings
    private static final int  LOCK_RETRIES   = 6;      // try up to ~6s
    private static final long LOCK_SLEEP_MS  = 1000;

    public ExcelUpdater(AppConfig cfg) {
        this.cfg = cfg;
    }

    /** Creates file if missing, writes latest-entry failsafe, backups, appends a new sheet, logs outcome. */
    public String appendPayload(ApachePayload p) throws Exception {
        Path file = Paths.get(cfg.excelFilePath);
        Path dir  = file.getParent() == null ? Paths.get(".") : file.getParent();
        Files.createDirectories(dir);

        // Ensure workbook exists
        if (!Files.exists(file)) {
            try (Workbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(file)) {
                wb.write(os);
            }
        }

        // Ensure folders exist
        Path backupDir = dir.resolve("backup");
        Path logsDir   = dir.resolve("logs");
        Files.createDirectories(backupDir);
        Files.createDirectories(logsDir);

        // --- Write "single latest entry" files FIRST (failsafe) ---
        writeLatestEntryFiles(backupDir, p);

        String sheetName = null;
        String logOutcome = "SUCCESS";
        String errorMsg = null;

        // --- Create fresh workbook backup, then prune older backups in backup/ ---
        Path newBackup = createFreshBackup(file, backupDir);
        pruneOlderBackups(backupDir, newBackup, baseName(file.getFileName().toString()));

        // --- Safe append: lock+retry then temp->atomic replace ---
        try {
            sheetName = doSafeAppend(file, p);
        } catch (Exception ex) {
            logOutcome = "ERROR";
            errorMsg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            // Do not delete backup or latest-entry files; they are for recovery.
            throw ex;
        } finally {
            writeMonthlyLog(logsDir, p, logOutcome, errorMsg);
        }

        return sheetName;
    }

    // -------------------- core write --------------------

    private String doSafeAppend(Path file, ApachePayload p) throws Exception {
        for (int i = 0; i < LOCK_RETRIES; i++) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                try (FileLock lock = ch.tryLock()) {
                    if (lock != null) {
                        return writeNewSheet(file, p);
                    }
                }
            } catch (Exception ignore) { /* retry */ }
            Thread.sleep(LOCK_SLEEP_MS);
        }
        // Even if we couldn't lock (Excel open?), still try atomic replace.
        return writeNewSheet(file, p);
    }

    private String writeNewSheet(Path file, ApachePayload p) throws Exception {
        try (InputStream is = Files.newInputStream(file);
             Workbook wb = WorkbookFactory.create(is)) {

            String sheetName = buildSheetName(p);
            Sheet sheet = wb.createSheet(sheetName);
            int row = 0;

            // Header
            writeRow(sheet, row++, "APACHE II Score from MDCalc.com on " + nowLocal(), null);
            writeRow(sheet, row++, "** All calculations should be rechecked by clinician prior to use **", null);
            row++;

            // Result summary
            writeRow(sheet, row++, "RESULT SUMMARY: " + ns(p.patientName) + " ; UHID= " + ns(p.uhid), null);

            // Optional computed values if present
            Object score  = val(p, "APACHE_Score");
            Object postop = val(p, "PostopMortality");
            Object nonop  = val(p, "NonopMortality");
            if (score  != null) writeRow(sheet, row++, score  + " points", null);
            if (postop != null) writeRow(sheet, row++, postop + "% estimated postoperative mortality", null);
            if (nonop  != null) writeRow(sheet, row++, nonop  + "% estimated nonoperative mortality", null);

            row++;
            writeRow(sheet, row++, "INPUTS:", null);

            writeRow(sheet, row++, "History of severe organ failure or immunocompromise —>", val(p, "ChronicHealth"));
            writeRow(sheet, row++, "Age —>",          suffix(val(p, "Age_years"),        " years"));
            writeRow(sheet, row++, "Temperature —>",  suffix(val(p, "Temperature_F"),    " F"));
            writeRow(sheet, row++, "Mean arterial pressure —>", suffix(val(p, "MAP_mmHg"), " mm Hg"));
            writeRow(sheet, row++, "pH —>", val(p, "pH"));
            writeRow(sheet, row++, "Heart rate/pulse —>", suffix(val(p, "HeartRate_bpm"), " beats/min"));
            writeRow(sheet, row++, "Respiratory rate —>", suffix(val(p, "RespRate_bpm"),  " breaths/min"));
            writeRow(sheet, row++, "Sodium —>",       suffix(val(p, "Na_mEqL"),          " mmol/L"));
            writeRow(sheet, row++, "Potassium —>",    suffix(val(p, "K_mEqL"),           " mmol/L"));
            writeRow(sheet, row++, "Creatinine —>",   suffix(val(p, "Creatinine_mgdl"),  " mg/dL"));
            writeRow(sheet, row++, "Acute renal failure —>", val(p, "AcuteRenalFailure"));
            writeRow(sheet, row++, "Hematocrit —>",   suffix(val(p, "Hematocrit_pct"),   " %"));
            writeRow(sheet, row++, "White blood cell count —>", suffix(val(p, "WBC_10e3uL"), " × 10⁹ cells/L"));
            writeRow(sheet, row++, "Glasgow Coma Scale —>", suffix(val(p, "GCS"), " points"));
            writeRow(sheet, row++, "FiO₂ —>", val(p, "FiO2_Category"));
            writeRow(sheet, row++, "PaO₂, mmHg —>", val(p, "PaO2_mmHg"));
            Object aagrad = val(p, "AaGradient_mmHg");
            if (aagrad != null) writeRow(sheet, row++, "A–a gradient, mmHg —>", aagrad);

            // Write to temp and atomically replace original (fallback if atomic not supported)
            Path tmp = tempSibling(file, "xlsx_");
            try (OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                wb.write(os);
            }
            try {
                Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, REPLACE_EXISTING);
            }
            return sheetName;
        }
    }

    // -------------------- latest-entry files --------------------

    /** Write/overwrite the "single latest entry" files in backup/: JSON + TXT. */
    private void writeLatestEntryFiles(Path backupDir, ApachePayload p) {
        try {
            // JSON (raw payload)
            String json = toJson(p);
            Files.writeString(backupDir.resolve("latest-entry.json"), json,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            // Human-readable TXT (minimal)
            String txt = buildEntryText(p);
            Files.write(backupDir.resolve("latest-entry.txt"), txt.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception ignore) {
            // never fail the main flow because of entry files
        }
    }

    private static String buildEntryText(ApachePayload p) {
        StringBuilder sb = new StringBuilder();
        sb.append("APACHE BRIDGE LATEST ENTRY").append(System.lineSeparator());
        sb.append("Timestamp: ").append(LocalDateTime.now().format(TS_LOG)).append(System.lineSeparator());
        sb.append("Patient: ").append(ns(p.patientName)).append(" ; UHID=").append(ns(p.uhid)).append(System.lineSeparator());
        sb.append("Score: ").append(str(val(p, "APACHE_Score"))).append(" ; ")
          .append("Postop%: ").append(str(val(p, "PostopMortality"))).append(" ; ")
          .append("Nonop%: ").append(str(val(p, "NonopMortality"))).append(System.lineSeparator());
        sb.append("---- INPUTS (subset) ----").append(System.lineSeparator());
        put(sb, "ChronicHealth", p);
        put(sb, "Age_years", p);
        put(sb, "Temperature_F", p);
        put(sb, "MAP_mmHg", p);
        put(sb, "pH", p);
        put(sb, "HeartRate_bpm", p);
        put(sb, "RespRate_bpm", p);
        put(sb, "Na_mEqL", p);
        put(sb, "K_mEqL", p);
        put(sb, "Creatinine_mgdl", p);
        put(sb, "AcuteRenalFailure", p);
        put(sb, "Hematocrit_pct", p);
        put(sb, "WBC_10e3uL", p);
        put(sb, "GCS", p);
        put(sb, "FiO2_Category", p);
        put(sb, "PaO2_mmHg", p);
        put(sb, "AaGradient_mmHg", p);
        return sb.toString();
    }

    private static void put(StringBuilder sb, String key, ApachePayload p) {
        Object v = val(p, key);
        if (v != null) sb.append(key).append(": ").append(v).append(System.lineSeparator());
    }

    // -------------------- backups --------------------

    /** Create a new timestamped backup inside backup/ (workbook-YYYYMMDD-HHmmSS.bak.xlsx). */
    private Path createFreshBackup(Path file, Path backupDir) throws Exception {
        if (!cfg.excelBackup) return null;
        String ts   = TS_FILE.format(LocalDateTime.now());
        Path bak    = backupDir.resolve("workbook-" + ts + ".bak.xlsx");
        Files.copy(file, bak, REPLACE_EXISTING);
        return bak;
    }

    /** Keep only the newest backup in backup/; delete all others. */
    private void pruneOlderBackups(Path backupDir, Path keep, String baseName) throws Exception {
        if (!cfg.excelBackup) return;
        try (Stream<Path> st = Files.list(backupDir)) {
            st.filter(p -> p.getFileName().toString().startsWith("workbook-") &&
                           p.getFileName().toString().endsWith(".bak.xlsx"))
              .filter(p -> !p.equals(keep))
              .sorted(Comparator.comparing(Path::toString))
              .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
        }
    }

    // -------------------- logging --------------------

    private void writeMonthlyLog(Path logsDir, ApachePayload p, String outcome, String error) {
        try {
            Files.createDirectories(logsDir);
            String month  = LOG_MONTH.format(LocalDateTime.now(ZoneId.systemDefault()));
            Path logFile  = logsDir.resolve("apache-bridge-" + month + ".log");

            String ts = TS_LOG.format(LocalDateTime.now());
            String score  = str(val(p, "APACHE_Score"));
            String postop = str(val(p, "PostopMortality"));
            String nonop  = str(val(p, "NonopMortality"));

            StringBuilder line = new StringBuilder();
            line.append(ts).append(" | ").append(outcome)
                .append(" | patient=").append(ns(p.patientName))
                .append(" | UHID=").append(ns(p.uhid))
                .append(" | score=").append(emptyToNA(score))
                .append(" | postop%=").append(emptyToNA(postop))
                .append(" | nonop%=").append(emptyToNA(nonop));
            if ("ERROR".equals(outcome) && error != null) {
                line.append(" | err=").append(error.replaceAll("[\\r\\n]", " "));
            }
            line.append(System.lineSeparator());

            Files.writeString(logFile, line.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (Exception ignore) { /* never fail on logging */ }
    }

    // -------------------- helpers --------------------

    private static void writeRow(Sheet s, int rowIdx, String left, Object right) {
        Row r = s.getRow(rowIdx);
        if (r == null) r = s.createRow(rowIdx);
        Cell c0 = r.getCell(0);
        if (c0 == null) c0 = r.createCell(0);
        c0.setCellValue(left == null ? "" : left);
        if (right != null) {
            Cell c1 = r.getCell(1);
            if (c1 == null) c1 = r.createCell(1);
            c1.setCellValue(String.valueOf(right));
        }
    }

    private static Object val(ApachePayload p, String key) {
        return (p != null && p.apacheInputs != null) ? p.apacheInputs.get(key) : null;
    }

    private String buildSheetName(ApachePayload p) {
        String date = TS_SHEET.format(LocalDateTime.now());
        String base;
        switch (cfg.sheetNaming) {
            case "UHID_DATE":
                base = (ns(p.uhid).isEmpty() ? "UHID" : ns(p.uhid)) + "_" + date;
                break;
            case "TIMESTAMP":
                base = date;
                break;
            default:
                base = (ns(p.patientName).isEmpty() ? "PATIENT" : ns(p.patientName))
                        .replaceAll("[\\\\/:*?\\[\\]]", "_") + "_" + date;
        }
        if (base.length() > 28) base = base.substring(0, 28); // Excel sheet name limit
        return base;
    }

    private static Path tempSibling(Path target, String prefix) throws Exception {
        Path dir = target.getParent() == null ? Paths.get(".") : target.getParent();
        return Files.createTempFile(dir, prefix, ".tmp");
    }

    private static String baseName(String filename) {
        int i = filename.lastIndexOf('.');
               return i > 0 ? filename.substring(0, i) : filename;
    }

    private static String ns(String s) { return s == null ? "" : s; }
    private static String nowLocal() { return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); }
    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
    private static String emptyToNA(String v) { return (v == null || v.isBlank()) ? "NA" : v; }

    /** Append a suffix (with spacing/units) only when a value is present; returns null if value is null/blank. */
    private static String suffix(Object v, String sfx) {
        if (v == null) return null;
        String t = String.valueOf(v).trim();
        return t.isEmpty() ? null : t + sfx;
    }

    // --- Minimal JSON for latest-entry.json without adding new deps ---
    @SuppressWarnings("unchecked")
    private static String toJson(ApachePayload p) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendJsonField(sb, "clientId",     p.clientId, true);
        appendJsonField(sb, "authToken",    p.authToken, true);
        appendJsonField(sb, "patientName",  p.patientName, true);
        appendJsonField(sb, "uhid",         p.uhid, true);
        appendJsonField(sb, "timestamp",    p.timestamp, true);
        appendJsonField(sb, "submissionId", p.submissionId, true);
        // apacheInputs
        sb.append("\"apacheInputs\":{");
        if (p.apacheInputs != null) {
            boolean first = true;
            for (Map.Entry<String,Object> e : p.apacheInputs.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(e.getKey())).append('"').append(':');
                Object val = e.getValue();
                if (val == null) sb.append("null");
                else if (val instanceof Number || val instanceof Boolean) sb.append(String.valueOf(val));
                else sb.append('"').append(escape(String.valueOf(val))).append('"');
            }
        }
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, String key, String val, boolean comma) {
        sb.append('"').append(escape(key)).append('"').append(':');
        if (val == null) sb.append("null");
        else sb.append('"').append(escape(val)).append('"');
        sb.append(',');
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
