package desk;

public class ServerAck {
    public String status;       // SUCCESS | ERROR
    public String submissionId; // echoed back when available
    public String clientId;
    public String message;      // human readable
    public String sheetName;    // for SUCCESS

    public static ServerAck success(String submissionId, String clientId, String msg) {
        ServerAck a = new ServerAck();
        a.status = "SUCCESS";
        a.submissionId = submissionId;
        a.clientId = clientId;
        a.message = msg;
        a.sheetName = msg != null && msg.startsWith("Saved to sheet: ") ? msg.substring("Saved to sheet: ".length()) : null;
        return a;
    }
    public static ServerAck error(String submissionId, String clientId, String msg) {
        ServerAck a = new ServerAck();
        a.status = "ERROR";
        a.submissionId = submissionId;
        a.clientId = clientId;
        a.message = msg;
        return a;
    }
}
