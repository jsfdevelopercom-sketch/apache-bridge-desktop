package desk;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Writes a single OS-specific watchdog script that keeps the headless desktop client running.
 *
 * Linux/macOS:
 *   - File: apachebridge-watchdog.sh
 *   - Run:  bash apachebridge-watchdog.sh
 *
 * Windows:
 *   - File: apachebridge-watchdog.ps1
 *   - Run:  powershell -ExecutionPolicy Bypass -File apachebridge-watchdog.ps1
 *
 * The script:
 *   - Ensures logs dir exists
 *   - Appends all events to watchdog.log
 *   - Maintains a PID file to avoid duplicate spawns
 *   - Always starts the true headless entrypoint:  java -cp "<JAR>" desk.ServiceMain
 *     (with -Djava.awt.headless=true and safe env guards)
 */
public final class WatchdogWriter {

    private WatchdogWriter() {}

    /**
     * Ensure a watchdog script exists and is executable.
     *
     * @param baseDir  Directory to place the script (e.g., ~/.apache-bridge or the app folder).
     * @param jarPath  Path to the shaded GUI jar (contains all classes; used as the classpath).
     * @param logsDir  Directory for log files (watchdog.log will be created here).
     * @return         Path to the created/updated script.
     */
    public static Path writeWatchdogScript(Path baseDir, Path jarPath, Path logsDir) throws Exception {
        if (baseDir == null) throw new IllegalArgumentException("baseDir is null");
        if (jarPath == null) throw new IllegalArgumentException("jarPath is null");
        if (logsDir == null) throw new IllegalArgumentException("logsDir is null");

        Files.createDirectories(baseDir);
        Files.createDirectories(logsDir);

        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final boolean isWindows = os.contains("win");

        Path script = baseDir.resolve(isWindows ? "apachebridge-watchdog.ps1" : "apachebridge-watchdog.sh");
        String content = isWindows
                ? renderWindowsPs1(
                        jarPath.toAbsolutePath().toString(),
                        logsDir.resolve("watchdog.log").toAbsolutePath().toString(),
                        logsDir.resolve("watchdog.pid").toAbsolutePath().toString())
                : renderUnixSh(
                        jarPath.toAbsolutePath().toString(),
                        logsDir.resolve("watchdog.log").toAbsolutePath().toString(),
                        logsDir.resolve("watchdog.pid").toAbsolutePath().toString());

        Files.writeString(script, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        // Make executable on *nix
        try {
            if (!isWindows) {
                script.toFile().setExecutable(true, false);
            }
        } catch (Throwable ignore) {}

        return script;
    }

    // ====================== Script templates ======================

    /**
     * Linux / macOS bash watchdog
     * Changes:
     *  - Launches desk.ServiceMain via -cp (never the GUI Main-Class)
     *  - Forces headless with JAVA_TOOL_OPTIONS and -Djava.awt.headless=true
     *  - Matches process by main class name "desk.ServiceMain" (stable)
     */
    private static String renderUnixSh(String jar, String log, String pid) {
        String safeJar = jar.replace("\"", "\\\"");
        String safeLog = log.replace("\"", "\\\"");
        String safePid = pid.replace("\"", "\\\"");
        String now     = LocalDateTime.now().toString();

        // We look for java processes whose args contain our main class.
        // This is resilient across quoting and paths.
        String className = "desk.ServiceMain";

        String guiClass = "desk.App";
        String controlPanelClass = "desk.ControlPanel";

        return "#!/usr/bin/env bash\n" +
               "set -u\n" +
               "JAR=\"" + safeJar + "\"\n" +
               "LOG=\"" + safeLog + "\"\n" +
               "PIDF=\"" + safePid + "\"\n" +
               "MAIN=\"" + className + "\"\n" +
               "GUI=\"" + guiClass + "\"\n" +
               "CONTROL_PANEL=\"" + controlPanelClass + "\"\n" +
               "mkdir -p \"$(dirname \"$LOG\")\" || true\n" +
               "echo \"[START " + now + "] Watchdog running (unix)\" >> \"$LOG\"\n" +
               "\n" +
               "kill_gui() {\n" +
               "  local target\n" +
               "  for target in \"$GUI\" \"$CONTROL_PANEL\"; do\n" +
               "    local ids\n" +
               "    ids=$(pgrep -f \"$target\" 2>/dev/null || true)\n" +
               "    if [ -n \"$ids\" ]; then\n" +
               "      for pid in $ids; do\n" +
               "        local cmd\n" +
               "        cmd=$(ps -o args= -p \"$pid\" 2>/dev/null || echo \"$target\")\n" +
               "        echo \"[WARN $(date)] Stopping stray GUI process $pid -> $cmd\" >> \"$LOG\"\n" +
               "        kill \"$pid\" 2>/dev/null || true\n" +
               "      done\n" +
               "    fi\n" +
               "  done\n" +
               "}\n" +
               "\n" +
               "is_running_pid() {\n" +
               "  local pid=\"$1\"\n" +
               "  if [ -z \"$pid\" ]; then return 1; fi\n" +
               "  if ps -p \"$pid\" > /dev/null 2>&1; then\n" +
               "    local cmd\n" +
               "    cmd=$(ps -o args= -p \"$pid\" 2>/dev/null || true)\n" +
               "    echo \"$cmd\" | grep -F -- \"$MAIN\" >/dev/null 2>&1\n" +
               "    return $?\n" +
               "  fi\n" +
               "  return 1\n" +
               "}\n" +
               "\n" +
               "start_client() {\n" +
               "  echo \"[INFO $(date)] Starting desktop client (ServiceMain)\" >> \"$LOG\"\n" +
               "  # Guard against GUI subsystems; enforce headless at JVM and env level\n" +
               "  export JAVA_TOOL_OPTIONS=\"-Djava.awt.headless=true\"\n" +
               "  export APACHE_BRIDGE_FORCE_HEADLESS=1\n" +
               "  export DISPLAY=\"\"\n" +
               "  nohup java -Djava.awt.headless=true -cp \"$JAR\" \"$MAIN\" >> \"$LOG\" 2>&1 &\n" +
               "  echo $! > \"$PIDF\"\n" +
               "}\n" +
               "\n" +
               "while true; do\n" +
               "  PID=\"\"\n" +
               "  if [ -f \"$PIDF\" ]; then\n" +
               "    PID=$(cat \"$PIDF\" 2>/dev/null || echo \"\")\n" +
               "  fi\n" +
               "\n" +
               "  kill_gui\n" +
               "  if ! is_running_pid \"$PID\"; then\n" +
               "    # Second-chance: find any running ServiceMain\n" +
               "    MPID=$(pgrep -af \"java\" | grep -F \"$MAIN\" | awk 'NR==1{print $1}')\n" +
               "    if [ -n \"$MPID\" ]; then\n" +
               "      echo \"$MPID\" > \"$PIDF\"\n" +
               "      echo \"[INFO $(date)] Found running client (pid=$MPID)\" >> \"$LOG\"\n" +
               "    else\n" +
               "      start_client\n" +
               "    fi\n" +
               "  fi\n" +
               "  sleep 30\n" +
               "done\n";
    }

    /**
     * Windows PowerShell watchdog
     * Changes:
     *  - Launches desk.ServiceMain with -cp instead of -jar App
     *  - Hidden window, headless JVM flag
     *  - Matches process by the main class token
     */
    private static String renderWindowsPs1(String jar, String log, String pid) {
        String escJar = jar.replace("\"", "`\"");
        String escLog = log.replace("\"", "`\"");
        String escPid = pid.replace("\"", "`\"");
        String now    = LocalDateTime.now().toString();

        String className = "desk.ServiceMain";
        String guiClass   = "desk.App";
        String controlPanelClass = "desk.ControlPanel";

        return ""
            + "$ErrorActionPreference = 'Stop'\n"
            + "$jar  = \"" + escJar + "\"\n"
            + "$log  = \"" + escLog + "\"\n"
            + "$pidf = \"" + escPid + "\"\n"
            + "$main = \"" + className + "\"\n"
            + "$gui  = \"" + guiClass + "\"\n"
            + "$panel = \"" + controlPanelClass + "\"\n"
            + "New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null\n"
            + "Add-Content -Path $log -Value \"[START " + now + "] Watchdog running (windows)\"\n"
            + "\n"
            + "function Stop-StrayGui {\n"
            + "  param([string]$Token)\n"
            + "  try {\n"
            + "    $procs = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like ('*' + $Token + '*') }\n"
            + "    foreach ($p in $procs) {\n"
            + "      Add-Content -Path $log -Value (\"[WARN \" + (Get-Date) + \"] Stopping stray GUI process pid=\" + $p.ProcessId + \", cmd=\" + $p.CommandLine + \"]\")\n"
            + "      try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop } catch {}\n"
            + "    }\n"
            + "  } catch {}\n"
            + "}\n"
            + "\n"
            + "function Test-IsOurClient {\n"
            + "  param([int]$Pid)\n"
            + "  if (-not $Pid) { return $false }\n"
            + "  try {\n"
            + "    $p = Get-CimInstance Win32_Process -Filter \"ProcessId=$Pid\"\n"
            + "    if (-not $p) { return $false }\n"
            + "    $cmd = $p.CommandLine\n"
            + "    if (-not $cmd) { return $false }\n"
            + "    return ($cmd -like (\"*\" + $main + \"*\"))\n"
            + "  } catch { return $false }\n"
            + "}\n"
            + "\n"
            + "function Start-Client {\n"
            + "  Add-Content -Path $log -Value \"[INFO $(Get-Date)] Starting desktop client (ServiceMain)\"\n"
            + "  $env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=true'\n"
            + "  $env:APACHE_BRIDGE_FORCE_HEADLESS = '1'\n"
            + "  $args = @('-Djava.awt.headless=true','-cp', $jar, $main)\n"
            + "  $p = Start-Process -FilePath 'java' -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError $log\n"
            + "  Set-Content -Path $pidf -Value $p.Id\n"
            + "}\n"
            + "\n"
            + "while ($true) {\n"
            + "  $pidVal = $null\n"
            + "  if (Test-Path $pidf) {\n"
            + "    try { $pidVal = [int](Get-Content -Path $pidf -ErrorAction Stop) } catch {}\n"
            + "  }\n"
            + "  Stop-StrayGui -Token $gui\n"
            + "  Stop-StrayGui -Token $panel\n"
            + "  if (-not (Test-IsOurClient -Pid $pidVal)) {\n"
            + "    try {\n"
            + "      $cand = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like ('*' + $main + '*') } | Select-Object -First 1\n"
            + "      if ($cand) {\n"
            + "        Set-Content -Path $pidf -Value $cand.ProcessId\n"
            + "        Add-Content -Path $log -Value (\"[INFO \" + (Get-Date) + \"] Found running client (pid=\" + $cand.ProcessId + \")\")\n"
            + "      } else {\n"
            + "        Start-Client\n"
            + "      }\n"
            + "    } catch { Start-Client }\n"
            + "  }\n"
            + "  Start-Sleep -Seconds 30\n"
            + "}\n";
    }
}
