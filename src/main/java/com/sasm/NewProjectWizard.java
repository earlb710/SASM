package com.sasm;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;

/**
 * "New Project" dialog — collects only a project name, working directory,
 * and target directory.
 *
 * <p>Three rows are stacked vertically on a scrollable canvas:</p>
 * <ol>
 *   <li>Name — free-text field</li>
 *   <li>Working Directory — free-text field with a folder-browse button
 *       (pre-filled with the user's home directory)</li>
 *   <li>Target Directory — free-text field with a folder-browse button
 *       (auto-defaults to {@code <workingDirectory>/target}; can be
 *       overridden by the user)</li>
 * </ol>
 * <p>OK and Cancel buttons appear at the bottom.  OK is enabled only when
 * both fields contain a non-blank value and the name is valid.</p>
 *
 * <p>Variant-specific settings (OS, output type, format variant, processor)
 * are now managed separately via the <em>Add Variant</em> dialog.</p>
 */
public class NewProjectWizard extends JDialog {

    /** Regex that every valid project name must fully match. */
    private static final String PROJECT_NAME_PATTERN = "[A-Za-z0-9_\\-]+";

    // ── form fields ──────────────────────────────────────────────────────────
    private final JTextField nameField        = new JTextField(50);
    private final JTextField dirField         = new JTextField(50);
    private final JButton    browseBtn        = new JButton("Browse…");
    private final JTextField targetField      = new JTextField(50);
    private final JButton    targetBrowseBtn  = new JButton("Browse…");

    /**
     * {@code true} once the user has manually edited the Target Directory
     * field (by typing or using its Browse button), after which automatic
     * synchronisation with the Working Directory is suppressed.
     */
    private boolean targetManuallySet = false;

    /**
     * Guards against {@code onTargetTyped()} firing during programmatic
     * updates to {@code targetField} (such as auto-sync from Working Directory).
     */
    private boolean suppressTargetManualFlag = false;

    // ── buttons ──────────────────────────────────────────────────────────────
    private final JButton okBtn     = new JButton("OK");
    private final JButton cancelBtn = new JButton("Cancel");

    // ── result state (read by caller after dispose) ───────────────────────────
    private boolean confirmed = false;
    /** Path of the {@code <name>.json} file written when the user clicks OK. */
    private java.io.File savedProjectFile;

    /** Existing project to pre-fill the form when editing properties, or null. */
    private ProjectFile preFill;

    public NewProjectWizard(Frame owner) {
        this(owner, null);
    }

    /**
     * Creates the wizard optionally pre-filled with existing project data.
     * When {@code existing} is non-null the dialog title changes to
     * "Project Properties" and the fields are seeded from the existing project.
     */
    public NewProjectWizard(Frame owner, ProjectFile existing) {
        super(owner, existing != null ? "Project Properties" : "New Project",
                true /* modal */);
        this.preFill = existing;
        buildUi();
        if (existing != null) {
            nameField.setText(existing.name != null ? existing.name : "");
            dirField.setText(existing.workingDirectory != null
                    ? existing.workingDirectory : "");
            if (existing.targetDirectory != null && !existing.targetDirectory.isEmpty()) {
                targetField.setText(existing.targetDirectory);
                targetManuallySet = true;
            } else {
                // Derive default from workingDirectory
                syncTargetDefault();
            }
            refreshOkButton();
        }
        pack();
        setResizable(true);
        setMinimumSize(new Dimension(600, 320));
        setLocationRelativeTo(owner);
    }

    // ── public accessors (usable by the caller after OK is pressed) ───────────

    /** Returns {@code true} if the user pressed OK, {@code false} for Cancel. */
    public boolean isConfirmed() { return confirmed; }

    public String getProjectName()      { return nameField.getText().trim(); }
    public String getWorkingDirectory() { return dirField.getText().trim(); }
    public String getTargetDirectory()  { return targetField.getText().trim(); }
    /** Returns the {@code .json} file written on OK, or {@code null} if cancelled. */
    public java.io.File getSavedProjectFile() { return savedProjectFile; }

    // ────────────────────────────────────────────────────────────────────────
    // UI construction
    // ────────────────────────────────────────────────────────────────────────

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));

        // ── title banner ────────────────────────────────────────────────────
        JLabel title = new JLabel(
                preFill != null ? "Project Properties" : "New Project",
                SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setBackground(new Color(0x2B, 0x57, 0x97));
        title.setForeground(Color.WHITE);
        title.setOpaque(true);
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.add(title, BorderLayout.CENTER);
        titlePanel.setPreferredSize(new Dimension(600, 36));
        add(titlePanel, BorderLayout.NORTH);

        // ── form canvas ──────────────────────────────────────────────────────
        JPanel canvas = new JPanel(new GridBagLayout());
        canvas.setBackground(Color.WHITE);

        int gbRow = 0;

        // ── Name ─────────────────────────────────────────────────────────────
        gbRow = addLabeledControl(canvas, gbRow, "Name:", nameField,
                "The project name is used as the source-file prefix (e.g. hello → hello.sasm).");

        // ── Working Directory ─────────────────────────────────────────────────
        dirField.setText(System.getProperty("user.home", "") + java.io.File.separator + "sasm");

        JPanel dirPanel = new JPanel(new BorderLayout(4, 0));
        dirPanel.setBackground(Color.WHITE);
        dirPanel.add(dirField, BorderLayout.CENTER);
        dirPanel.add(browseBtn, BorderLayout.EAST);
        gbRow = addLabeledControl(canvas, gbRow, "Working Directory:", dirPanel,
                "All generated files (.sasm, .o, binary) will be written into this folder.");

        // ── Target Directory ──────────────────────────────────────────────────
        syncTargetDefault();   // populate from the default dirField value

        JPanel targetPanel = new JPanel(new BorderLayout(4, 0));
        targetPanel.setBackground(Color.WHITE);
        targetPanel.add(targetField, BorderLayout.CENTER);
        targetPanel.add(targetBrowseBtn, BorderLayout.EAST);
        gbRow = addLabeledControl(canvas, gbRow, "Target Directory:", targetPanel,
                "Compiled output (object files, binaries) will be placed here. "
                + "Defaults to <working directory>/target.");

        // spacer row keeps content pinned to top
        GridBagConstraints sp = new GridBagConstraints();
        sp.gridx = 0; sp.gridy = gbRow; sp.gridwidth = 2;
        sp.fill = GridBagConstraints.BOTH;
        sp.weighty = 1.0;
        canvas.add(new JPanel(), sp);

        add(canvas, BorderLayout.CENTER);

        // ── button row ──────────────────────────────────────────────────────
        okBtn.setEnabled(false);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        btnRow.add(okBtn);
        btnRow.add(cancelBtn);
        add(btnRow, BorderLayout.SOUTH);

        // ── wire events ─────────────────────────────────────────────────────
        browseBtn.addActionListener(e -> browseForDirectory());
        targetBrowseBtn.addActionListener(e -> browseForTargetDirectory());

        DocumentListener refreshListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshOkButton(); }
            public void removeUpdate(DocumentEvent e) { refreshOkButton(); }
            public void changedUpdate(DocumentEvent e) { refreshOkButton(); }
        };
        // When the working directory changes, auto-update the target field
        // unless the user has already manually chosen a custom target path.
        DocumentListener dirSyncListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onDirChanged(); }
            public void removeUpdate(DocumentEvent e) { onDirChanged(); }
            public void changedUpdate(DocumentEvent e) { onDirChanged(); }
        };
        // Typing directly into targetField marks it as manually set.
        DocumentListener targetManualListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onTargetTyped(); }
            public void removeUpdate(DocumentEvent e) { onTargetTyped(); }
            public void changedUpdate(DocumentEvent e) { onTargetTyped(); }
        };
        nameField.getDocument().addDocumentListener(refreshListener);
        dirField.getDocument().addDocumentListener(refreshListener);
        dirField.getDocument().addDocumentListener(dirSyncListener);
        targetField.getDocument().addDocumentListener(targetManualListener);
        okBtn.addActionListener(e -> onOkPressed());
        cancelBtn.addActionListener(e -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });

        refreshOkButton();
    }

    // ── layout helpers ────────────────────────────────────────────────────────

    /**
     * Appends a label + control row (plus a small italic hint line) to {@code canvas}
     * and returns the next available GridBag row index.
     */
    private static int addLabeledControl(JPanel canvas, int startRow,
                                         String labelText, Component control,
                                         String hint) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = startRow;
        lc.anchor = GridBagConstraints.NORTHWEST;
        lc.insets = new Insets(startRow == 0 ? 16 : 12, 16, 2, 8);
        JLabel lbl = new JLabel(labelText, SwingConstants.RIGHT);
        lbl.setFont(new Font("SansSerif", Font.BOLD, 12));
        canvas.add(lbl, lc);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.gridy = startRow;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.anchor = GridBagConstraints.NORTHWEST;
        fc.insets = new Insets(startRow == 0 ? 16 : 12, 0, 2, 16);
        canvas.add(control, fc);

        int nextRow = startRow + 1;

        if (hint != null) {
            GridBagConstraints hc = new GridBagConstraints();
            hc.gridx = 1; hc.gridy = nextRow;
            hc.anchor = GridBagConstraints.NORTHWEST;
            hc.insets = new Insets(0, 2, 4, 16);
            JLabel hintLbl = new JLabel(hint, SwingConstants.LEFT);
            hintLbl.setFont(new Font("SansSerif", Font.ITALIC, 10));
            hintLbl.setForeground(Color.DARK_GRAY);
            canvas.add(hintLbl, hc);
            nextRow++;
        }

        return nextRow;
    }

    // ── event handlers ────────────────────────────────────────────────────────

    private void browseForDirectory() {
        FileDialog fd = new FileDialog(this, "Select Working Directory", FileDialog.LOAD);
        String currentDir = dirField.getText().trim();
        if (!currentDir.isEmpty()) {
            fd.setDirectory(currentDir);
        }
        System.setProperty("apple.awt.fileDialogForDirectories", "true");
        fd.setVisible(true);
        System.setProperty("apple.awt.fileDialogForDirectories", "false");

        String dir = fd.getDirectory();
        if (dir != null && !dir.isEmpty()) {
            dirField.setText(dir);
            refreshOkButton();
        }
    }

    private void browseForTargetDirectory() {
        FileDialog fd = new FileDialog(this, "Select Target Directory", FileDialog.LOAD);
        String currentDir = targetField.getText().trim();
        if (!currentDir.isEmpty()) {
            fd.setDirectory(currentDir);
        } else {
            String workDir = dirField.getText().trim();
            if (!workDir.isEmpty()) fd.setDirectory(workDir);
        }
        System.setProperty("apple.awt.fileDialogForDirectories", "true");
        fd.setVisible(true);
        System.setProperty("apple.awt.fileDialogForDirectories", "false");

        String dir = fd.getDirectory();
        if (dir != null && !dir.isEmpty()) {
            targetManuallySet = true;
            targetField.setText(dir);
            refreshOkButton();
        }
    }

    /**
     * Sets the Target Directory to {@code <workingDir>/target} without marking
     * it as manually set.  Called during initial layout and when loading a
     * project whose {@code targetDirectory} was null.
     */
    private void syncTargetDefault() {
        suppressTargetManualFlag = true;
        try {
            String workDir = dirField.getText().trim();
            if (!workDir.isEmpty()) {
                targetField.setText(workDir + java.io.File.separator + "target");
            } else {
                targetField.setText("");
            }
        } finally {
            suppressTargetManualFlag = false;
        }
    }

    /**
     * Called when the Working Directory field changes.  If the user has not
     * yet manually set the Target Directory, the target is automatically kept
     * in sync as {@code <workingDir>/target}.
     */
    private void onDirChanged() {
        if (!targetManuallySet) {
            syncTargetDefault();
        }
        refreshOkButton();
    }

    /**
     * Called when the user types directly into the Target Directory field.
     * Marks the field as manually set so auto-sync stops.
     */
    private void onTargetTyped() {
        if (!suppressTargetManualFlag) {
            targetManuallySet = true;
        }
    }

    /** Enables OK only when both fields are non-blank and the name is valid. */
    private void refreshOkButton() {
        String name = nameField.getText().trim();
        boolean nameOk = !name.isEmpty() && name.matches(PROJECT_NAME_PATTERN);
        boolean ready = nameOk
                     && !dirField.getText().trim().isEmpty();
        okBtn.setEnabled(ready);
    }

    /** Validates the name, writes the project JSON, then closes the dialog. */
    private void onOkPressed() {
        String name = nameField.getText().trim();
        if (!name.matches(PROJECT_NAME_PATTERN)) {
            showError("Project name may only contain letters, digits,\n"
                    + "underscores (_) and hyphens (-).\n"
                    + "Spaces and other special characters are not allowed.");
            return;
        }
        try {
            savedProjectFile = saveProject(name);
        } catch (java.io.IOException ex) {
            showError("Could not save project file:\n" + ex.getMessage());
            return;
        }
        confirmed = true;
        dispose();
    }

    /**
     * Writes the project file to {@code <workingDirectory>/<name>.json}.
     */
    private java.io.File saveProject(String name) throws java.io.IOException {
        ProjectFile pf = new ProjectFile();
        pf.name             = name;
        pf.workingDirectory = dirField.getText().trim();
        pf.targetDirectory  = targetField.getText().trim();

        java.io.File dir = new java.io.File(pf.workingDirectory);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException(
                    "Cannot create working directory: " + dir.getAbsolutePath());
        }
        // Create the core/ subdirectory for main source files
        java.io.File coreDir = new java.io.File(dir, "core");
        if (!coreDir.exists() && !coreDir.mkdirs()) {
            throw new java.io.IOException(
                    "Cannot create core directory: " + coreDir.getAbsolutePath());
        }
        // Create the lib/ subdirectory for standard/shared library files
        java.io.File libDir = new java.io.File(dir, "lib");
        if (!libDir.exists() && !libDir.mkdirs()) {
            throw new java.io.IOException(
                    "Cannot create lib directory: " + libDir.getAbsolutePath());
        }
        // Copy standard library files from the system library into the
        // new project's lib/ directory so they are immediately available.
        copySystemLibraries(libDir);

        java.io.File out = new java.io.File(dir, name + ".json");
        JsonLoader.saveProjectFile(pf, out);
        return out;
    }

    // ── system library helpers ────────────────────────────────────────────────

    /**
     * Copies all {@code .sasm} files from every OS subdirectory under
     * {@code system/lib/} into the project's {@code lib/} directory.
     * Variant-specific subdirectories (e.g. {@code system/lib/linux/console/})
     * are also scanned and their files are copied.
     *
     * <p>The system library directory is located using the same strategy as
     * {@link JsonLoader}: first relative to the current working directory,
     * then relative to the running JAR file.</p>
     *
     * <p>If the system library directory cannot be found, no files are
     * copied and no error is raised — the project simply starts with an
     * empty {@code lib/} directory.</p>
     */
    private static void copySystemLibraries(java.io.File destLibDir) {
        java.io.File sysLib = resolveSystemLibDir();
        if (sysLib == null || !sysLib.isDirectory()) return;

        java.io.File[] osDirs = sysLib.listFiles(java.io.File::isDirectory);
        if (osDirs == null) return;

        for (java.io.File osDir : osDirs) {
            // Copy .sasm files directly under the OS directory
            copySasmFiles(osDir, destLibDir);

            // Copy .sasm files from variant subdirectories (e.g. console/)
            java.io.File[] variantDirs = osDir.listFiles(java.io.File::isDirectory);
            if (variantDirs == null) continue;
            for (java.io.File variantDir : variantDirs) {
                copySasmFiles(variantDir, destLibDir);
            }
        }
    }

    /**
     * Copies all {@code .sasm} files from {@code srcDir} into {@code destDir},
     * skipping any file whose name already exists in the destination.
     */
    private static void copySasmFiles(java.io.File srcDir, java.io.File destDir) {
        java.io.File[] sasmFiles = srcDir.listFiles(
                (d, n) -> n.endsWith(".sasm"));
        if (sasmFiles == null) return;

        for (java.io.File src : sasmFiles) {
            java.io.File dest = new java.io.File(destDir, src.getName());
            if (!dest.exists()) {
                try {
                    java.nio.file.Files.copy(src.toPath(), dest.toPath());
                } catch (java.io.IOException ex) {
                    System.err.println("Warning: could not copy system library "
                            + src.getName() + ": " + ex.getMessage());
                }
            }
        }
    }

    /**
     * Locates the {@code system/lib/} directory using the same search
     * strategy as the JSON loader:
     * <ol>
     *   <li>{@code <cwd>/system/lib/}</li>
     *   <li>{@code <jar-dir>/../../system/lib/} (Maven target/ layout)</li>
     * </ol>
     *
     * @return the directory, or {@code null} if it cannot be found
     */
    private static java.io.File resolveSystemLibDir() {
        // 1. Try cwd/system/lib/
        java.io.File candidate = new java.io.File("system/lib");
        if (candidate.isDirectory()) return candidate;

        // 2. Try <jar-dir>/../../system/lib/
        try {
            java.io.File jarDir = new java.io.File(
                    NewProjectWizard.class.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()).getParentFile();
            java.io.File fromJar = new java.io.File(jarDir,
                    "../../system/lib").getCanonicalFile();
            if (fromJar.isDirectory()) return fromJar;
        } catch (Exception ignored) {
            // fall through
        }

        return null;
    }

    private void showError(String msg) {
        JDialog err = new JDialog(this, "Error", true);
        err.setLayout(new BorderLayout(8, 8));
        JTextArea ta = new JTextArea(msg, 6, 50);
        ta.setEditable(false);
        err.add(ta, BorderLayout.CENTER);
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> err.dispose());
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bp.add(ok);
        err.add(bp, BorderLayout.SOUTH);
        err.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { err.dispose(); }
        });
        err.pack();
        err.setLocationRelativeTo(this);
        err.setVisible(true);
    }
}