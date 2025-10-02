package main;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ServerAck {

    public enum Status { SUCCESS, ERROR }

    @JsonProperty("clientId")
    private String clientId;

    @JsonProperty("status")
    private Status status;

    @JsonProperty("message")
    private String message;

    @JsonProperty("uhid")
    private String uhid;

    @JsonProperty("patientName")
    private String patientName;

    public ServerAck() { }

    public ServerAck(String clientId, Status status, String message,
                     String uhid, String patientName) {
        this.clientId = clientId;
        this.status = status;
        this.message = message;
        this.uhid = uhid;
        this.patientName = patientName;
    }

    public String getClientId() { return clientId; }
    public Status getStatus() { return status; }
    public String getMessage() { return message; }
    public String getUhid() { return uhid; }
    public String getPatientName() { return patientName; }

    public void setClientId(String clientId) { this.clientId = clientId; }
    public void setStatus(Status status) { this.status = status; }
    public void setMessage(String message) { this.message = message; }
    public void setUhid(String uhid) { this.uhid = uhid; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
}
