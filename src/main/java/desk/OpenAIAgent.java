package desk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal, resilient OpenAI client using java.net.http (no extra deps). */
public final class OpenAIAgent {

    private OpenAIAgent() {}

    /**
     * Ask OpenAI for commentary on the summary.
     * Returns a short, formatted text or a clear fallback message.
     */
    public static String commentary(String apiKey, String summaryText, int retries) {
        if (apiKey == null || apiKey.isBlank()) {
            return "(AI commentary unavailable: missing openai.key in application.properties)";
        }
        String prompt =
                "You are a clinical data analyst. Given this MONTHLY snapshot of ICU APACHE-II entries, " +
                "write a crisp 120-180 word note highlighting key signals ONLY: spikes in WBC, acidosis (low pH), " +
                "outliers in age, and any notable high APACHE scores. Avoid PHI; refer to patients generically. " +
                "Prefer bullet points; end with a one-line caution. Snapshot:\n\n" + summaryText;

        // Build payload
        String body = "{"
                + "\"model\":\"gpt-4o-mini\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"You are a concise clinical analytics assistant.\"},"
                + "{\"role\":\"user\",\"content\":" + jsonString(prompt) + "}"
                + "],"
                + "\"temperature\":0.2,"
                + "\"max_tokens\":300"
                + "}";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(45))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        int attempts = Math.max(1, retries);
        for (int i = 1; i <= attempts; i++) {
            try {
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    // naive parse of first choice.message.content to avoid adding JSON libs
                    String text = pickField(resp.body(), "\"content\":\"", "\"");
                    if (text == null || text.isBlank()) {
                        return "(AI commentary not returned)";
                    }
                    return text.replace("\\n", "\n").replace("\\\"", "\"");
                } else {
                    String err = "(OpenAI error " + resp.statusCode() + ")";
                    if (i == attempts) return err;
                    sleep(800L * i);
                }
            } catch (Exception e) {
                if (i == attempts) return "(AI commentary unavailable: " + e.getMessage() + ")";
                sleep(800L * i);
            }
        }
        return "(AI commentary unavailable)";
    }

    // --- helpers ---
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String pickField(String json, String start, String end) {
        int i = json.indexOf(start);
        if (i < 0) return null;
        i += start.length();
        int j = json.indexOf(end, i);
        if (j < 0) return null;
        return json.substring(i, j);
    }
}
