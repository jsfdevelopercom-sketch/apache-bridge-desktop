package desk;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ApachePayload {

	   public String clientId;
	    public String authToken;
	    public String patientName;
	    public String uhid;
	    public String timestamp;
	    public String submissionId;

	    // Flattened vitals/labs are nested under apacheInputs (free-form map is OK)
	    public Map<String, Object> apacheInputs;

  

    public String getClientId() { return clientId; }
    public String getAuthToken() { return authToken; }
    public String getPatientName() { return patientName; }
    public String getUhid() { return uhid; }
    public Map<String, Object> getApacheInputs() { return apacheInputs; }
    public String getTimestamp() { return timestamp; }

    public void setClientId(String clientId) { this.clientId = clientId; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public void setUhid(String uhid) { this.uhid = uhid; }
    public void setApacheInputs(Map<String, Object> apacheInputs) { this.apacheInputs = apacheInputs; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
