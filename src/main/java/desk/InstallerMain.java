package desk;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Files;
import java.nio.file.Path;

public final class InstallerMain {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (Installer.isInstalled()) {
                    JOptionPane.showMessageDialog(null,
                            "APACHE Bridge is already installed.\nYou can launch the Control Panel from the desktop shortcut.",
                            "Already Installed", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }

                JFileChooser fc = new JFileChooser();
                fc.setDialogTitle("Select (or create) ICU Excel Workbook (.xlsx)");
                fc.setFileFilter(new FileNameExtensionFilter("Excel (.xlsx)", "xlsx"));

                int r = fc.showSaveDialog(null);
                if (r != JFileChooser.APPROVE_OPTION) return;

                Path chosen = fc.getSelectedFile().toPath();
                // If user typed a new file, ensure it exists (empty workbook).
                if (!Files.exists(chosen)) {
                    ExcelScaffolder.createEmptyWorkbook(chosen); // tiny helper below
                }

                Installer.performInstall(chosen.toString());
                int ok = JOptionPane.showConfirmDialog(null,
                        "Installed successfully.\n\nEnable autostart of background client?",
                        "Enable Autostart", JOptionPane.YES_NO_OPTION);
                if (ok == JOptionPane.YES_OPTION) {
                    AutoStartManager.enable();
                }

                JOptionPane.showMessageDialog(null,
                        "Done.\nUse the desktop shortcut \"APACHE Bridge Control Panel\" to open the GUI.",
                        "Success", JOptionPane.INFORMATION_MESSAGE);

            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "Install failed: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
