package desk;

public class AppConfig {
    // WS & identity
    public String serverWsUrl;
    public String clientId;
    public String authToken;

    // Excel
    public String excelFilePath;
    public String excelResultSummaryCell = "A1";
    public boolean excelBackup = true;
    /** PATIENTNAME_DATE | UHID_DATE | TIMESTAMP */
    public String sheetNaming = "PATIENTNAME_DATE";

    // Reconnect backoff (ms)
    public long initialReconnectMillis = 2000;
    public long maxReconnectMillis = 30000;
    public String openAiKey;
    
    // NEW: optional logsDir (used by WatchdogWriter / Installer)
    public String logsDir;
}
