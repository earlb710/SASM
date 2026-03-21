package com.sasm;

import java.awt.*;
import java.awt.event.*;

/**
 * "New Project" dialog — collects only a project name and working directory.
 *
 * <p>Two rows are stacked vertically on a scrollable canvas:</p>
 * <ol>
 *   <li>Name — free-text field</li>
 *   <li>Working Directory — free-text field with a folder-browse button
 *       (pre-filled with the user's home directory)</li>
 * </ol>
 * <p>OK and Cancel buttons appear at the bottom.  OK is enabled only when
 * both fields contain a non-blank value and the name is valid.</p>
 *
 * <p>Variant-specific settings (OS, output type, format variant, processor)
 * are now managed separately via the <em>Add Variant</em> dialog.</p>
 */
public class NewProjectWizard extends Dialog {

    /** Regex that every valid project name must fully match. */
    private static final String PROJECT_NAME_PATTERN = "[A-Za-z0-9_\\-]+";

    // ── form fields ──────────────────────────────────────────────────────────
    private final TextField nameField  = new TextField(50);
    private final TextField dirField   = new TextField(50);
    private final Button    browseBtn  = new Button("Browse…");

    // ── buttons ──────────────────────────────────────────────────────────────
    private final Button okBtn     = new Button("OK");
    private final Button cancelBtn = new Button("Cancel");

    // ── result state (read by caller after dispose) ───────────────────────────
    private boolean confirmed = false;
    /** Path of the {@code <name>.json} file written when the user clicks OK. */
    private java.io.File savedProjectFile;

    public NewProjectWizard(Frame owner) {
        super(owner, "New Project", true /* modal */);
        buildUi();
        pack();
        setResizable(true);
        setMinimumSize(new Dimension(600, 260));
        setLocationRelativeTo(owner);
    }

    // ── public accessors (usable by the caller after OK is pressed) ───────────

    /** Returns {@code true} if the user pressed OK, {@code false} for Cancel. */
    public boolean isConfirmed() { return confirmed; }

    public String getProjectName()      { return nameField.getText().trim(); }
    public String getWorkingDirectory() { return dirField.getText().trim(); }
    /** Returns the {@code .json} file written on OK, or {@code null} if cancelled. */
    public java.io.File getSavedProjectFile() { return savedProjectFile; }

    // ────────────────────────────────────────────────────────────────────────
    // UI construction
    // ────────────────────────────────────────────────────────────────────────

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));

        // ── title banner ────────────────────────────────────────────────────
        Label title = new Label("New Project", Label.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setBackground(new Color(0x2B, 0x57, 0x97));
        title.setForeground(Color.WHITE);
        Panel titlePanel = new Panel(new BorderLayout());
        titlePanel.add(title, BorderLayout.CENTER);
        titlePanel.setPreferredSize(new Dimension(600, 36));
        add(titlePanel, BorderLayout.NORTH);

        // ── form canvas ──────────────────────────────────────────────────────
        Panel canvas = new Panel(new GridBagLayout());
        canvas.setBackground(Color.WHITE);

        int gbRow = 0;

        // ── Name ─────────────────────────────────────────────────────────────
        gbRow = addLabeledControl(canvas, gbRow, "Name:", nameField,
                "The project name is used as the source-file prefix (e.g. hello → hello.sasm).");

        // ── Working Directory ─────────────────────────────────────────────────
        dirField.setText(System.getProperty("user.home", "") + java.io.File.separator + "sasm");

        Panel dirPanel = new Panel(new BorderLayout(4, 0));
        dirPanel.setBackground(Color.WHITE);
        dirPanel.add(dirField, BorderLayout.CENTER);
        dirPanel.add(browseBtn, BorderLayout.EAST);
        gbRow = addLabeledControl(canvas, gbRow, "Working Directory:", dirPanel,
                "All generated files (.sasm, .o, binary) will be written into this folder.");

        // spacer row keeps content pinned to top
        GridBagConstraints sp = new GridBagConstraints();
        sp.gridx = 0; sp.gridy = gbRow; sp.gridwidth = 2;
        sp.fill = GridBagConstraints.BOTH;
        sp.weighty = 1.0;
        canvas.add(new Panel(), sp);

        add(canvas, BorderLayout.CENTER);

        // ── button row ──────────────────────────────────────────────────────
        okBtn.setEnabled(false);
        Panel btnRow = new Panel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        btnRow.add(okBtn);
        btnRow.add(cancelBtn);
        add(btnRow, BorderLayout.SOUTH);

        // ── wire events ─────────────────────────────────────────────────────
        browseBtn.addActionListener(e -> browseForDirectory());
        nameField.addTextListener(e -> refreshOkButton());
        dirField.addTextListener(e -> refreshOkButton());
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
    private static int addLabeledControl(Panel canvas, int startRow,
                                         String labelText, Component control,
                                         String hint) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = startRow;
        lc.anchor = GridBagConstraints.NORTHWEST;
        lc.insets = new Insets(startRow == 0 ? 16 : 12, 16, 2, 8);
        Label lbl = new Label(labelText, Label.RIGHT);
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
            Label hintLbl = new Label(hint, Label.LEFT);
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

        java.io.File dir = new java.io.File(pf.workingDirectory);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException(
                    "Cannot create working directory: " + dir.getAbsolutePath());
        }
        java.io.File out = new java.io.File(dir, name + ".json");
        JsonLoader.saveProjectFile(pf, out);
        return out;
    }

    private void showError(String msg) {
        Dialog err = new Dialog(this, "Error", true);
        err.setLayout(new BorderLayout(8, 8));
        TextArea ta = new TextArea(msg, 6, 50, TextArea.SCROLLBARS_NONE);
        ta.setEditable(false);
        err.add(ta, BorderLayout.CENTER);
        Button ok = new Button("OK");
        ok.addActionListener(e -> err.dispose());
        Panel bp = new Panel(new FlowLayout(FlowLayout.CENTER));
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