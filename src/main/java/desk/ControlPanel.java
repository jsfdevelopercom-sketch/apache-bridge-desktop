package desk;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicButtonUI;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.*;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Control Panel GUI
 * - Shows Installer only on first run (no config) via a modal wizard.
 * - When installed, tabs exclude Installer; Desktop shortcut creation is handled by Installer.performInstall().
 * - Status LED is shown consistently on every functional tab via wrapWithStatus().
 * - Light, safe UI styling (rounded buttons) without extra libs.
 */
public class ControlPanel {

    private static JFrame frame;
    private static JTextField excelPathField;
    private static JTextField clientIdField;
    private static JPasswordField authField;
    private static JTextArea logArea;
    private static JLabel statusLbl;

    // Status widgets (shared across tabs)
    private static JLabel ledLabel;
    private static JLabel statusMsg;
    private static Timer  pollTimer;

    private static final AtomicBoolean installedOnce = new AtomicBoolean(false);

    public static void launchUI() {
        SwingUtilities.invokeLater(ControlPanel::createAndShow);
    }

    private static void createAndShow() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore) {}

        frame = new JFrame("APACHE Bridge – Desktop (JSFDev)");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(900, 680);
        frame.setLocationRelativeTo(null);

        boolean installed = isInstalled();
        installedOnce.set(installed);

        if (!installed) {
            // First run → modal quick-setup wizard
            showInstallerWizard();
            // After wizard, re-check
            installed = isInstalled();
            installedOnce.set(installed);
        }

        // Build the main UI (without Installer tab if already installed)
        frame.setContentPane(buildTabs(installed));
        frame.setVisible(true);

        // Status polling for ALL functional tabs
        startPollingStatus();
    }

    private static JComponent buildTabs(boolean installed) {
        JTabbedPane tabs = new JTabbedPane();

        if (!installed) {
            // During first-run flow (user canceled wizard), still allow install from a tab
            tabs.addTab("Installer", buildInstallerTab());
        }

        tabs.addTab("Settings",  wrapWithStatus(buildSettingsTab()));
        tabs.addTab("Insights",  wrapWithStatus(buildInsightsTab()));
        tabs.addTab("Safety",    wrapWithStatus(buildSafetyTab()));
        tabs.addTab("Logs",      wrapWithStatus(buildLogsTab()));
        return tabs;
    }

    /** Slim status strip placed above content (shared across tabs via wrapWithStatus). */
    private static JPanel wrapWithStatus(JPanel content) {
        JPanel root = new JPanel(new BorderLayout());
        root.add(buildStatusStrip(), BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        return root;
    }

    private static JPanel buildStatusStrip() {
        JPanel s = new JPanel(new BorderLayout());
        s.setBorder(new EmptyBorder(8, 12, 8, 12));
        s.setBackground(new Color(246, 248, 251));

        ledLabel = new JLabel("●");
        ledLabel.setFont(ledLabel.getFont().deriveFont(Font.BOLD, 18f));
        ledLabel.setForeground(new Color(220, 0, 0)); // red default

        statusMsg = new JLabel("Disconnected");
        statusMsg.setFont(statusMsg.getFont().deriveFont(Font.PLAIN, 12f));

        JButton refresh = fancyButton("Refresh");
        refresh.addActionListener(e -> showStatusNow());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(ledLabel);
        left.add(statusMsg);

        s.add(left, BorderLayout.WEST);
        s.add(refresh, BorderLayout.EAST);
        return s;
    }

    private static void startPollingStatus() {
        if (pollTimer != null) pollTimer.cancel();
        pollTimer = new Timer("status-poll", true);
        pollTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                SwingUtilities.invokeLater(ControlPanel::showStatusNow);
            }
        }, 0, 2000);
    }

    private static void showStatusNow() {
        StatusReporter.Status st = StatusReporter.read();
        if (st == null) {
            setLed(false, "Disconnected (no status)");
            return;
        }
        if (st.connected) {
            long age = Math.max(0, Instant.now().getEpochSecond() - st.epochTs);
            setLed(true, age > 7 ? "Connected (last ping " + age + "s)" : "Connected");
        } else {
            String msg = (st.lastError == null || st.lastError.isBlank()) ? "Disconnected" : "Disconnected: " + st.lastError;
            setLed(false, msg);
        }
    }

    private static void setLed(boolean on, String msg) {
        if (ledLabel == null || statusMsg == null) return;
        ledLabel.setForeground(on ? new Color(0, 170, 70) : new Color(220, 0, 0));
        statusMsg.setText(msg);
    }

    // ---------- First run modal wizard ----------

    private static void showInstallerWizard() {
        final JDialog dlg = new JDialog((Frame) null, "Quick Setup - Installer", true);
        dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dlg.setSize(640, 360);
        dlg.setLocationRelativeTo(null);

        JPanel root = new JPanel();
        root.setBorder(new EmptyBorder(16,16,16,16));
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Welcome to APACHE Bridge Desktop");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        root.add(title);
        root.add(space(8));

        JTextArea intro = new JTextArea("""
            This app writes APACHE-II entries from your phone into your ICU Excel workbook safely.
            • Creates one rolling backup and a latest-entry file each write.
            • A background service auto-starts on login (headless).
            • You can open this Control Panel anytime via the desktop shortcut to view status, logs, and insights.
            
            Select your ICU Excel workbook to continue. If you don’t have one, we will create an empty .xlsx for you.
            """);
        intro.setEditable(false);
        intro.setBackground(root.getBackground());
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        root.add(intro);
        root.add(space(10));

        JPanel pickRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton pick = fancyButton("Choose ICU Excel File…");
        JTextField pathField = new JTextField(36); pathField.setEditable(false);
        pick.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Select ICU Excel Workbook (or choose a folder to create)");
            fc.setFileFilter(new FileNameExtensionFilter("Excel (.xlsx)", "xlsx"));
            int r = fc.showOpenDialog(dlg);
            if (r == JFileChooser.APPROVE_OPTION) {
                pathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        pickRow.add(pick);
        pickRow.add(pathField);
        root.add(pickRow);

        root.add(space(10));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = fancyButton("Cancel");
        JButton next   = fancyButton("Install");
        cancel.addActionListener(e -> { dlg.dispose(); });
        next.addActionListener(e -> {
            try {
                String chosen = pathField.getText().trim();
                if (chosen.isBlank()) {
                    // Offer to create a new workbook in a chosen folder
                    JFileChooser dir = new JFileChooser();
                    dir.setDialogTitle("Choose a folder to create ICU.xlsx");
                    dir.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    int r = dir.showOpenDialog(dlg);
                    if (r != JFileChooser.APPROVE_OPTION) return;
                    Path newXlsx = dir.getSelectedFile().toPath().resolve("ICU.xlsx");
                    // create empty workbook if not exists
                    if (!Files.exists(newXlsx)) ExcelScaffolder.createEmptyWorkbook(newXlsx);
                    chosen = newXlsx.toString();
                } else {
                    // If user selected a non-existent file with .xlsx, create it empty
                    Path p = Path.of(chosen);
                    if (!Files.exists(p)) {
                        if (chosen.toLowerCase().endsWith(".xlsx")) {
                            ExcelScaffolder.createEmptyWorkbook(p);
                        } else {
                            JOptionPane.showMessageDialog(dlg, "Please choose an existing .xlsx file or leave blank to create one.");
                            return;
                        }
                    }
                }

                Installer.performInstall(chosen);
                // enable watchdog + autostart by default; user can disable later
                AutoStartManager.enable();

                JOptionPane.showMessageDialog(dlg, "Installed successfully.\nA desktop shortcut was created.");
                dlg.dispose();

                // Rebuild main UI without Installer tab
                frame.setContentPane(buildTabs(true));
                frame.revalidate();
                frame.repaint();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dlg, "Install failed: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        actions.add(cancel);
        actions.add(next);
        root.add(actions);

        dlg.setContentPane(root);
        dlg.setVisible(true);
    }

    // ---------- Tabs (Installer shows only when not yet installed) ----------

    private static JPanel buildInstallerTab() {
        JPanel p = panel();
        p.add(h1("Quick Setup"));

        JTextArea intro = ta("""
            This desktop app receives APACHE-II submissions from your phone and writes them into
            your ICU Excel workbook with safe writes (rolling backup + last entry file).
            A background service auto-starts on login; the Control Panel opens via a Desktop shortcut.
            """);
        p.add(intro);

        JButton chooseBtn = fancyButton("Choose ICU Excel File…");
        excelPathField = new JTextField(50);
        excelPathField.setEditable(false);
        chooseBtn.addActionListener(e -> chooseExcel());

        JPanel row = row(chooseBtn, excelPathField);
        p.add(row);

        JButton installBtn = fancyButton("Install & Enable Auto-start");
        installBtn.addActionListener(e -> doInstall(true));

        JButton justInstallBtn = fancyButton("Install (no auto-start)");
        justInstallBtn.addActionListener(e -> doInstall(false));

        p.add(row(installBtn, justInstallBtn));
        statusLbl = new JLabel(" ");
        p.add(statusLbl);

        return p;
    }

    private static JPanel buildSettingsTab() {
        JPanel p = panel();
        p.add(h1("Settings"));

        AppConfig cfg = ConfigLoader.load();

        p.add(lab("Client ID:"));
        clientIdField = new JTextField(cfg.clientId, 28);
        p.add(clientIdField);

        p.add(lab("Shared Auth Token:"));
        authField = new JPasswordField(cfg.authToken, 28);
        p.add(authField);

        JButton save = fancyButton("Save Settings");
        save.addActionListener(e -> saveSettings());
        p.add(space(6));
        p.add(save);

        return p;
    }

    private static JPanel buildInsightsTab() {
        JPanel p = panel();
        p.add(h1("Monthly Snapshot"));

        JComboBox<String> months = new JComboBox<>();
        YearMonth now = YearMonth.now();
        for (int i = 0; i < 12; i++) {
            YearMonth ym = now.minusMonths(i);
            months.addItem(ym.toString());
        }
        JButton run = fancyButton("Generate Summary");
        JTextArea out = ta("");
        out.setRows(16);

        run.addActionListener(e -> {
            try {
                AppConfig cfg = ConfigLoader.load();
                String ym = (String) months.getSelectedItem();
                String summary = MonthlyReport.buildSummary(cfg.excelFilePath, ym);

                // Optional AI commentary
                String apiKey = cfg.openAiKey; // from properties
                if (apiKey != null && !apiKey.isBlank()) {
                    String ai = OpenAIAgent.commentary(apiKey, summary, 2);
                    out.setText(summary + "\n\nAI commentary:\n" + ai);
                } else {
                    out.setText(summary + "\n\n(AI commentary disabled: set openai.api.key in Settings)");
                }
            } catch (Exception ex) {
                out.setText("Error: " + ex.getMessage());
            }
        });

        p.add(row(new JLabel("Month:"), months, run));
        p.add(out);
        return p;
    }

    private static JPanel buildSafetyTab() {
        JPanel p = panel();
        p.add(h1("Safety / Recovery"));

        JButton restoreBtn = fancyButton("Restore from last backup");
        restoreBtn.addActionListener(e -> {
            int ok = JOptionPane.showConfirmDialog(frame,
                    "This will overwrite the current Excel file with the last backup.\n" +
                            "Use only if your latest write failed.\n\nContinue?",
                    "Confirm Restore", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok == JOptionPane.YES_OPTION) {
                try {
                    AppConfig cfg = ConfigLoader.load();
                    MonthlyReport.restoreLastBackup(cfg.excelFilePath);
                    JOptionPane.showMessageDialog(frame, "Restored from backup.");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Restore failed: " + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton readdBtn = fancyButton("Re-add latest single entry");
        readdBtn.addActionListener(e -> {
            int ok = JOptionPane.showConfirmDialog(frame,
                    "This will read backup/latest-entry.json and append it again as a new sheet.\n" +
                            "Proceed?", "Confirm Re-add", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) {
                try {
                    AppConfig cfg = ConfigLoader.load();
                    MonthlyReport.readdLatestEntry(cfg);
                    JOptionPane.showMessageDialog(frame, "Latest entry re-added.");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Failed: " + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        p.add(restoreBtn);
        p.add(readdBtn);
        return p;
    }

    private static JPanel buildLogsTab() {
        JPanel p = panel();
        p.add(h1("Logs"));
        logArea = ta("");
        logArea.setRows(20);
        JButton refresh = fancyButton("Show This Month Log");
        refresh.addActionListener(e -> {
            try {
                AppConfig cfg = ConfigLoader.load();
                String txt = MonthlyReport.readThisMonthLog(cfg.excelFilePath);
                logArea.setText(txt == null ? "(no log yet)" : txt);
            } catch (Exception ex) {
                logArea.setText("Error: " + ex.getMessage());
            }
        });
        p.add(refresh);
        p.add(new JScrollPane(logArea));
        return p;
    }

    // ---------- Actions ----------

    private static void chooseExcel() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select ICU Excel Workbook");
        fc.setFileFilter(new FileNameExtensionFilter("Excel (.xlsx)", "xlsx"));
        int r = fc.showOpenDialog(frame);
        if (r == JFileChooser.APPROVE_OPTION) {
            excelPathField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }
 // --- minimal installer-only entrypoint ---
    public static void launchInstallerOnly() {
        SwingUtilities.invokeLater(ControlPanel::createAndShowInstallerOnly);
    }

    private static void createAndShowInstallerOnly() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore) {}

        frame = new JFrame("APACHE Bridge – Installer (JSFDev)");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(720, 520);
        frame.setLocationRelativeTo(null);

        // Only the Installer tab, nothing else, no status polling
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Installer", buildInstallerTab());
        frame.setContentPane(tabs);
        frame.setVisible(true);
    }

    private static void doInstall(boolean autostart) {
        try {
            String excel = excelPathField.getText().trim();
            Path targetXlsx;

            if (excel.isBlank()) {
                // No file chosen → ask for a folder, then create ICU.xlsx there
                JFileChooser dir = new JFileChooser();
                dir.setDialogTitle("Choose a folder to create ICU.xlsx");
                dir.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int r = dir.showOpenDialog(frame);
                if (r != JFileChooser.APPROVE_OPTION) return;

                targetXlsx = dir.getSelectedFile().toPath().resolve("ICU.xlsx");
                if (!Files.exists(targetXlsx)) {
                    Files.createDirectories(targetXlsx.getParent());
                    try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                         java.io.OutputStream os = Files.newOutputStream(targetXlsx)) {
                        wb.write(os);
                    }
                }
            } else {
                // User provided a path: ensure it exists (or create an empty .xlsx at that path)
                targetXlsx = Path.of(excel);
                if (!Files.exists(targetXlsx)) {
                    if (!excel.toLowerCase().endsWith(".xlsx")) {
                        JOptionPane.showMessageDialog(frame, "Selected path is not an .xlsx file.");
                        return;
                    }
                    Files.createDirectories(
                        (targetXlsx.getParent() != null) ? targetXlsx.getParent() : targetXlsx.toAbsolutePath().getParent()
                    );
                    try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                         java.io.OutputStream os = Files.newOutputStream(targetXlsx)) {
                        wb.write(os);
                    }
                }
            }

            // Run installer + optional autostart watchdog
            Installer.performInstall(targetXlsx.toString());
            if (autostart) AutoStartManager.enable();

            statusLbl.setText("Installed" + (autostart ? " with autostart." : "."));

            // Hide Installer tab after success
            frame.setContentPane(buildTabs(true));
            frame.revalidate();
            frame.repaint();

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Install failed: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void saveSettings() {
        try {
            String cid = clientIdField.getText().trim();
            String tok = new String(authField.getPassword()).trim();
            if (cid.isBlank() || tok.isBlank()) {
                JOptionPane.showMessageDialog(frame, "Client ID and Token are required.");
                return;
            }
            Installer.writeOrUpdateConfig(cid, tok, null);
            JOptionPane.showMessageDialog(frame, "Saved.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Save failed: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------- Helpers ----------

    private static boolean isInstalled() {
        try {
            AppConfig cfg = ConfigLoader.load();
            if (cfg == null) return false;
            if (blank(cfg.clientId) || blank(cfg.authToken) || blank(cfg.serverWsUrl) || blank(cfg.excelFilePath))
                return false;
            return Files.exists(Path.of(cfg.excelFilePath));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean blank(String s){ return s == null || s.trim().isEmpty(); }

    private static JPanel panel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(14,14,14,14));
        p.setBackground(Color.WHITE);
        return p;
    }

    private static JLabel h1(String s){
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 20f));
        return l;
    }

    private static JLabel lab(String s){ return new JLabel(s); }

    private static JPanel row(Component... c){
        JPanel r=new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        r.setOpaque(false);
        for (Component x : c) r.add(x);
        return r;
    }

    private static JTextArea ta(String s){
        JTextArea a=new JTextArea(s);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setEditable(false);
        a.setBackground(new Color(252, 253, 255));
        a.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(225, 230, 240)),
                new EmptyBorder(8,8,8,8)
        ));
        return a;
    }

    private static Component space(int h){ return Box.createVerticalStrut(h); }

    /** Light rounded button with consistent padding. */
    private static JButton fancyButton(String text) {
        JButton b = new JButton(text);
        b.setUI(new BasicButtonUI());
        b.setBackground(new Color(0x3FA8FF));
        b.setForeground(new Color(0x00233F));
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(8,14,8,14));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setOpaque(true);
        b.setBorder(new RoundedBorder(new Color(0x2B8EDB)));
        return b;
    }

    /** Simple rounded border for fancy buttons. */
    private static class RoundedBorder extends javax.swing.border.AbstractBorder {
        private final Color line;
        RoundedBorder(Color line){ this.line = line; }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(line);
            g2.drawRoundRect(x, y, w-1, h-1, 14, 14);
            g2.dispose();
        }
        @Override public Insets getBorderInsets(Component c){ return new Insets(6,10,6,10); }
        @Override public Insets getBorderInsets(Component c, Insets in){ return getBorderInsets(c); }
    }
}
