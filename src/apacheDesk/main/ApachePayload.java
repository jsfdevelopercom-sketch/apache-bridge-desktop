package main;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ApachePayload {

    @JsonProperty("clientId")
    private String clientId;

    @JsonProperty("authToken")
    private String authToken;

    @JsonProperty("patientName")
    private String patientName;

    @JsonProperty("uhid")
    private String uhid;

    @JsonProperty("apacheInputs")
    private Map<String, Object> apacheInputs;

    @JsonProperty("timestamp")
    private Instant timestamp;

    public String getClientId() { return clientId; }
    public String getAuthToken() { return authToken; }
    public String getPatientName() { return patientName; }
    public String getUhid() { return uhid; }
    public Map<String, Object> getApacheInputs() { return apacheInputs; }
    public Instant getTimestamp() { return timestamp; }

    public void setClientId(String clientId) { this.clientId = clientId; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public void setUhid(String uhid) { this.uhid = uhid; }
    public void setApacheInputs(Map<String, Object> apacheInputs) { this.apacheInputs = apacheInputs; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
