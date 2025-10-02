package main;


public class AppConfig {
    private final String serverWsUrl;
    private final String clientId;
    private final String clientAuthToken;

    private final String excelFilePath;
    private final String resultSummaryCell;
    private final boolean safeWriteBackup;
    private final String sheetNaming;

    private final long initialReconnectMillis;
    private final long maxReconnectMillis;

    public AppConfig(String serverWsUrl, String clientId, String clientAuthToken,
                     String excelFilePath, String resultSummaryCell,
                     boolean safeWriteBackup, String sheetNaming,
                     long initialReconnectMillis, long maxReconnectMillis) {
        this.serverWsUrl = serverWsUrl;
        this.clientId = clientId;
        this.clientAuthToken = clientAuthToken;
        this.excelFilePath = excelFilePath;
        this.resultSummaryCell = resultSummaryCell;
        this.safeWriteBackup = safeWriteBackup;
        this.sheetNaming = sheetNaming;
        this.initialReconnectMillis = initialReconnectMillis;
        this.maxReconnectMillis = maxReconnectMillis;
    }

    public String getServerWsUrl() { return serverWsUrl; }
    public String getClientId() { return clientId; }
    public String getClientAuthToken() { return clientAuthToken; }
    public String getExcelFilePath() { return excelFilePath; }
    public String getResultSummaryCell() { return resultSummaryCell; }
    public boolean isSafeWriteBackup() { return safeWriteBackup; }
    public String getSheetNaming() { return sheetNaming; }
    public long getInitialReconnectMillis() { return initialReconnectMillis; }
    public long getMaxReconnectMillis() { return maxReconnectMillis; }
}
