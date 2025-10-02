package main;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import main.*;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class ExcelUpdater {
    private static final Logger log = LoggerFactory.getLogger(ExcelUpdater.class);
    private final AppConfig cfg;

    public ExcelUpdater(AppConfig cfg) {
        this.cfg = cfg;
    }

    public void appendPayload(ApachePayload payload) throws Exception {
        File excelFile = SafeFileOps.ensureFileExists(cfg.getExcelFilePath());

        if (cfg.isSafeWriteBackup()) {
            SafeFileOps.backupFile(excelFile);
        }

        File tempOut = File.createTempFile("apachebridge_", ".xlsx");
        try (Workbook wb = loadOrCreateWorkbook(excelFile);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(tempOut))) {

            String sheetName = makeSheetName(payload);
            Sheet sheet = wb.createSheet(sheetName);

            int r = 0;
            r = writeRow(sheet, r, "Patient Name", payload.getPatientName());
            r = writeRow(sheet, r, "UHID", payload.getUhid());

            String summary = "Result Summary: " + payload.getPatientName() + "    UHID=" + payload.getUhid();
            setCellByA1Notation(sheet, cfg.getResultSummaryCell(), summary);
            r = writeRow(sheet, r, "Result Summary", summary);

            String ts = payload.getTimestamp() == null ? LocalDateTime.now().toString() : payload.getTimestamp().toString();
            r = writeRow(sheet, r, "Timestamp (UTC)", ts);

            if (payload.getApacheInputs() != null) {
                for (Map.Entry<String, Object> e : payload.getApacheInputs().entrySet()) {
                    r = writeRow(sheet, r, e.getKey(), String.valueOf(e.getValue()));
                }
            }

            autosizeFirstTwoColumns(sheet);
            wb.write(os);
        } catch (Exception e) {
            log.error("Excel update failed", e);
            tempOut.delete();
            throw e;
        }

        try {
            SafeFileOps.atomicReplace(tempOut, excelFile);
        } catch (Exception e) {
            tempOut.delete();
            throw e;
        }
    }

    private Workbook loadOrCreateWorkbook(File excelFile) {
        try (InputStream is = new BufferedInputStream(new FileInputStream(excelFile))) {
            if (is.available() > 0) {
                return new XSSFWorkbook(is);
            }
        } catch (Exception ignore) { }
        return new XSSFWorkbook();
    }

    private String makeSheetName(ApachePayload p) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        String base;
        switch (cfg.getSheetNaming()) {
            case "UHID_DATE":
                base = safe(p.getUhid()) + "_" + now;
                break;
            case "TIMESTAMP":
                base = now;
                break;
            case "PATIENTNAME_DATE":
            default:
                base = safe(p.getPatientName()) + "_" + now;
                break;
        }
        base = base.replaceAll("[\\\\/?*\\[\\]]", "-");
        return base.length() > 31 ? base.substring(0, 31) : base;
    }

    private String safe(String s) {
        return (s == null || s.isBlank()) ? "UNKNOWN" : s.trim();
    }

    private int writeRow(Sheet sheet, int rowIndex, String key, String value) {
        Row row = sheet.createRow(rowIndex);
        Cell c0 = row.createCell(0, CellType.STRING);
        Cell c1 = row.createCell(1, CellType.STRING);
        c0.setCellValue(key == null ? "" : key);
        c1.setCellValue(value == null ? "" : value);
        return rowIndex + 1;
    }

    private void setCellByA1Notation(Sheet sheet, String a1, String value) {
        try {
            int[] rc = a1ToRowCol(a1);
            Row row = sheet.getRow(rc[0]);
            if (row == null) row = sheet.createRow(rc[0]);
            Cell cell = row.createCell(rc[1], CellType.STRING);
            cell.setCellValue(value);
        } catch (Exception e) {
            log.warn("Invalid resultSummaryCell '{}': {}", a1, e.toString());
        }
    }

    private int[] a1ToRowCol(String a1) {
        String s = a1.trim().toUpperCase();
        int i = 0;
        int col = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            col = col * 26 + (s.charAt(i) - 'A' + 1);
            i++;
        }
        int row = Integer.parseInt(s.substring(i)) - 1;
        return new int[]{row, col - 1};
    }

    private void autosizeFirstTwoColumns(Sheet sheet) {
        try { sheet.autoSizeColumn(0); } catch (Exception ignore) {}
        try { sheet.autoSizeColumn(1); } catch (Exception ignore) {}
    }
}
