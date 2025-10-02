package desk;

public class InstallerApp {
  public static void main(String[] args) {
    // minimal: show installer dialog only
    javax.swing.SwingUtilities.invokeLater(() -> {
      // Either reuse ControlPanel in "installer-only" mode,
      // or show a dedicated InstallerDialog.
      ControlPanel.launchInstallerOnly(); // implement this method to open the installer tab/dialog directly
    });
  }
}
