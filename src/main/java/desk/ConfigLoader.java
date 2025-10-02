package desk;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public class ConfigLoader {

	
	 private static Path configDir() {
	        return Paths.get(System.getProperty("user.home"), "apache-bridge");
	    }

	    private static Path configPath() {
	        return configDir().resolve("application.properties");
	    }
    public static AppConfig load() {
        Properties p = new Properties();
        Path cp = configPath();
        try (InputStream is =  Files.newInputStream(configPath(),StandardOpenOption.READ)) //ConfigLoader.class.getResourceAsStream("/application.properties")) 
        {
        	System.out.println("Config path: "+ cp.toAbsolutePath());
            if (is != null) p.load(is);
            else System.out.println("[Config] application.properties not found on classpath; using defaults");
        } catch (Exception e) {
            System.out.println("[Config] Failed to read application.properties: " + e.getMessage());
        }

        AppConfig cfg = new AppConfig();
        cfg.serverWsUrl = get(p, "server.ws.url", "wss://apache-bridge-server-production.up.railway.app/ws/desktop");
        cfg.clientId = get(p, "client.id", "NURSING-STATION-01");
        cfg.authToken = get(p, "auth.token", "");

        cfg.excelFilePath = get(p, "excel.file.path", "C:/APACHEBridge/icu_apache.xlsx");
        cfg.excelResultSummaryCell = get(p, "excel.result.cell", "A1");
        cfg.excelBackup = Boolean.parseBoolean(get(p, "excel.backup", "true"));
        cfg.sheetNaming = get(p, "sheet.naming", "PATIENTNAME_DATE");

        cfg.initialReconnectMillis = Long.parseLong(get(p, "reconnect.initial.ms", "2000"));
        cfg.maxReconnectMillis = Long.parseLong(get(p, "reconnect.max.ms", "30000"));
        cfg.openAiKey     = p.getProperty("openai.api.key", "").trim();
        cfg.logsDir = p.getProperty("logs.dir","");
        
        System.out.println("[Config] WS=" + cfg.serverWsUrl + " excel=" + cfg.excelFilePath);
        return cfg;
    }

    private static String get(Properties p, String key, String def) {
        String v = p.getProperty(key);
        if (v == null) 
        	{
        	System.out.println("property "+ key +" is null");
        	return def;
        	}
        v = v.trim();
        return v.isEmpty() ? def : v;
    }
}
