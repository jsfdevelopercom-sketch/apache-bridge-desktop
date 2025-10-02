package desk;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads your ICU workbook and summarizes sheets for a selected YearMonth.
 * Matches the sheet naming produced by ExcelUpdater (…_yyyy-MM-dd_HH-mm).
 * (Your writer format reference: ExcelUpdater#buildSheetName)  // matches writer format
 */
public final class MonthlyReport {

    private static final DateTimeFormatter TS_SHEET = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    private static final Pattern RESULT_SUMMARY = Pattern.compile("^RESULT SUMMARY:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UHID_IN_SUMMARY = Pattern.compile("UHID\\s*=\\s*([A-Za-z0-9_-]+)");

    private MonthlyReport() {}

    public static String buildSummary(String excelPath, String ymString) throws Exception {
        if (excelPath == null || excelPath.isBlank()) {
            return "No Excel workbook configured.";
        }
        YearMonth ym = YearMonth.parse(ymString); // e.g. 2025-09

        List<Entry> list = new ArrayList<>();

        Path p = Path.of(excelPath);
        if (!Files.exists(p)) return "Workbook not found: " + excelPath;

        try (InputStream is = Files.newInputStream(p);
             Workbook wb = WorkbookFactory.create(is)) {

            int nsheets = wb.getNumberOfSheets();
            for (int i = 0; i < nsheets; i++) {
                Sheet s = wb.getSheetAt(i);
                if (s == null) continue;

                YearMonth sheetMonth = parseMonthFromSheetName(s.getSheetName());
                if (sheetMonth == null || !sheetMonth.equals(ym)) continue;

                Entry e = scanEntry(s);
                if (e != null) list.add(e);
            }
        }

        // Sort by time desc, if available
        list.sort(Comparator.comparing((Entry e) -> e.when == null ? Instant.EPOCH : e.when).reversed());

        StringBuilder out = new StringBuilder();
        out.append("Month: ").append(ym).append("\n");
        out.append("Entries: ").append(list.size()).append("\n\n");
        int idx = 1;
        for (Entry e : list) {
            out.append(idx++).append(". ")
               .append(e.patient == null ? "(unknown)" : e.patient)
               .append(" ; UHID=").append(e.uhid == null ? "?" : e.uhid)
               .append(e.points == null ? "" : (" ; Score=" + e.points))
               .append("\n");
        }
        if (list.isEmpty()) {
            out.append("(No entries found for ").append(ym).append(")\n");
        }
        return out.toString();
    }

    public static void restoreLastBackup(String excelPath) throws Exception {
        Path excel = Path.of(excelPath);
        Path bakDir = excel.getParent().resolve("backup");
        Optional<Path> newest = Files.list(bakDir)
                .filter(f -> f.getFileName().toString().startsWith("workbook-") &&
                             f.getFileName().toString().endsWith(".bak.xlsx"))
                .sorted(Comparator.reverseOrder())
                .findFirst();
        if (newest.isEmpty()) throw new IllegalStateException("No backup found.");
        Files.copy(newest.get(), excel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static void readdLatestEntry(AppConfig cfg) throws Exception {
        Path excel = Path.of(cfg.excelFilePath);
        Path bakDir = excel.getParent().resolve("backup");
        Path json = bakDir.resolve("latest-entry.json");
        if (!Files.exists(json)) throw new IllegalStateException("No latest-entry.json present.");
        String raw = Files.readString(json);
        ApachePayload p = SimpleJson.parsePayload(raw);
        ExcelUpdater updater = new ExcelUpdater(cfg);
        updater.appendPayload(p);
    }

    public static String readThisMonthLog(String excelPath) throws Exception {
        Path excel = Path.of(excelPath);
        Path logs = excel.getParent().resolve("logs");
        YearMonth ym = YearMonth.now();
        String fn = "apache-bridge-" + ym + ".log";
        Path f = logs.resolve(fn);
        if (!Files.exists(f)) return null;
        return Files.readString(f);
    }

    // ---------- helpers ----------

    private static YearMonth parseMonthFromSheetName(String sheetName) {
        // Expect suffix _yyyy-MM-dd_HH-mm
        int idx = sheetName.lastIndexOf('_');
        if (idx < 0) return null;
        String tail = sheetName.substring(idx + 1);
        try {
            LocalDateTime ldt = LocalDateTime.parse(tail, TS_SHEET);
            return YearMonth.of(ldt.getYear(), ldt.getMonth());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Entry scanEntry(Sheet s) {
        Entry e = new Entry();
        // walk first ~40 rows; find "RESULT SUMMARY:" in col0
        for (int r = 0; r < Math.min(40, s.getLastRowNum() + 1); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            Cell c0 = row.getCell(0);
            if (c0 == null) continue;
            String txt = get(c0);
            if (txt == null) continue;

            // Try to capture timestamp from header line "APACHE II Score ... on 2025-09-21 10:15"
            if (r == 0 && txt.toLowerCase(Locale.ROOT).contains("apache ii score")) {
                // best-effort: the exact date format was "yyyy-MM-dd HH:mm" in the writer
                // but we keep it optional; no hard fail if format differs.
            }

            Matcher m = RESULT_SUMMARY.matcher(txt);
            if (m.find()) {
                e.patient = m.group(1).trim();
                // Extract UHID out of that same line if present
                Matcher uh = UHID_IN_SUMMARY.matcher(txt);
                if (uh.find()) e.uhid = uh.group(1);
            }

            if (txt.startsWith("APACHE") && txt.contains("points")) {
                e.points = tryParseInt(txt.replaceAll("\\D+", ""));
            }
        }
        // If we never saw anything meaningful, return null so we don't overcount
        if (e.patient == null && e.uhid == null && e.points == null) return null;
        return e;
    }

    private static Integer tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    private static String get(Cell c) {
        if (c == null) return null;
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> String.valueOf(c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> null;
        };
    }

    private static final class Entry {
        String patient;
        String uhid;
        Integer points;
        Instant when; // currently unused
    }
}
