package com.sasm;

import java.awt.*;
import java.awt.event.*;

/**
 * "New Project" dialog — single flat-form canvas.
 *
 * <p>Four fields are stacked vertically on one canvas:</p>
 * <ol>
 *   <li>Name — free-text field</li>
 *   <li>Working Directory — free-text field with a folder-browse button</li>
 *   <li>Operating System — pop-list (blank default; Linux / Windows)</li>
 *   <li>Variant — pop-list (blank default; populated when OS changes)</li>
 * </ol>
 * <p>OK and Cancel buttons appear at the bottom.</p>
 */
public class NewProjectWizard extends Dialog {

    // ── form fields ──────────────────────────────────────────────────────────
    private final TextField nameField    = new TextField(50);
    private final TextField dirField     = new TextField(50);
    private final Button    browseBtn    = new Button("Browse…");
    private final Choice    osChoice     = new Choice();
    private final Choice    variantChoice = new Choice();

    // ── buttons ──────────────────────────────────────────────────────────────
    private final Button okBtn     = new Button("OK");
    private final Button cancelBtn = new Button("Cancel");

    // ── result state (read by caller after dispose) ───────────────────────────
    private boolean confirmed = false;

    // ── OS data ──────────────────────────────────────────────────────────────
    private OsDefinition currentDef;

    public NewProjectWizard(Frame owner) {
        super(owner, "New Project", true /* modal */);
        buildUi();
        pack();
        setResizable(true);
        setMinimumSize(new Dimension(560, 260));
        setLocationRelativeTo(owner);
    }

    // ── public accessors (usable by the caller after OK is pressed) ───────────

    /** Returns {@code true} if the user pressed OK, {@code false} for Cancel. */
    public boolean isConfirmed() { return confirmed; }

    public String getProjectName()     { return nameField.getText().trim(); }
    public String getWorkingDirectory(){ return dirField.getText().trim(); }
    public String getSelectedOs()      { return selectedText(osChoice); }
    public String getSelectedVariant() { return selectedText(variantChoice); }

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
        titlePanel.setPreferredSize(new Dimension(560, 36));
        add(titlePanel, BorderLayout.NORTH);

        // ── canvas (all four rows) ───────────────────────────────────────────
        Panel canvas = new Panel(new GridBagLayout());
        canvas.setBackground(Color.WHITE);

        // Row 0 – Name
        addFormRow(canvas, 0, "Name:", nameField, null);

        // Row 1 – Working Directory
        Panel dirPanel = new Panel(new BorderLayout(4, 0));
        dirPanel.setBackground(Color.WHITE);
        dirPanel.add(dirField, BorderLayout.CENTER);
        dirPanel.add(browseBtn, BorderLayout.EAST);
        addFormRow(canvas, 1, "Working Directory:", dirPanel, null);

        // Row 2 – Operating System
        osChoice.addItem("");           // blank default
        osChoice.addItem("Linux");
        osChoice.addItem("Windows");
        addFormRow(canvas, 2, "Operating System:", osChoice, null);

        // Row 3 – Variant (blank until OS is chosen)
        variantChoice.addItem("");      // blank default
        addFormRow(canvas, 3, "Variant:", variantChoice,
                   "Populated after an operating system is selected.");

        // spacer row keeps content pinned to the top
        GridBagConstraints sp = new GridBagConstraints();
        sp.gridx = 0; sp.gridy = 4; sp.gridwidth = 3;
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
        osChoice.addItemListener(e -> onOsChanged());
        variantChoice.addItemListener(e -> refreshOkButton());
        nameField.addTextListener(e -> refreshOkButton());
        dirField.addTextListener(e -> refreshOkButton());
        okBtn.addActionListener(e -> { confirmed = true; dispose(); });
        cancelBtn.addActionListener(e -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });
    }

    /**
     * Adds a label + control (+ optional hint) as a single form row.
     *
     * @param canvas  the parent panel
     * @param row     GridBag row index
     * @param label   row label text
     * @param control the AWT Component to place in the value column
     * @param hint    optional small hint text placed below the control (may be null)
     */
    private static void addFormRow(Panel canvas, int row,
                                   String label, Component control, String hint) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = row * 2;
        lc.anchor = GridBagConstraints.NORTHWEST;
        lc.insets = new Insets(row == 0 ? 16 : 10, 16, 2, 8);
        Label lbl = new Label(label, Label.RIGHT);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        canvas.add(lbl, lc);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.gridy = row * 2;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.anchor = GridBagConstraints.NORTHWEST;
        fc.insets = new Insets(row == 0 ? 16 : 10, 0, 2, 16);
        canvas.add(control, fc);

        if (hint != null) {
            GridBagConstraints hc = new GridBagConstraints();
            hc.gridx = 1; hc.gridy = row * 2 + 1;
            hc.anchor = GridBagConstraints.NORTHWEST;
            hc.insets = new Insets(0, 0, 4, 16);
            Label hintLbl = new Label(hint, Label.LEFT);
            hintLbl.setFont(new Font("SansSerif", Font.ITALIC, 10));
            hintLbl.setForeground(Color.DARK_GRAY);
            canvas.add(hintLbl, hc);
        }
    }

    // ── event handlers ────────────────────────────────────────────────────────

    /** Opens a FileDialog to let the user navigate to a directory. */
    private void browseForDirectory() {
        // AWT has no dedicated directory chooser; we use FileDialog and take
        // the directory portion of whatever path the user accepts.
        FileDialog fd = new FileDialog(this, "Select Working Directory", FileDialog.LOAD);
        // On macOS this property switches to a folder-picker mode.
        System.setProperty("apple.awt.fileDialogForDirectories", "true");
        fd.setVisible(true);
        System.setProperty("apple.awt.fileDialogForDirectories", "false");

        String dir = fd.getDirectory();
        if (dir != null && !dir.isEmpty()) {
            // FileDialog.getDirectory() already returns the parent directory path;
            // we use it directly whether the user selected a file or a folder.
            dirField.setText(dir);
            refreshOkButton();
        }
    }

    /** Called when the OS pop-list changes — reloads variants. */
    private void onOsChanged() {
        variantChoice.removeAll();
        variantChoice.addItem("");          // keep blank as first entry
        currentDef = null;

        String os = selectedText(osChoice);
        if (os.isEmpty()) {
            refreshOkButton();
            return;
        }

        try {
            currentDef = JsonLoader.load(os);
        } catch (Exception ex) {
            showError("Could not load definition for " + os + ":\n" + ex.getMessage());
            refreshOkButton();
            return;
        }

        if (currentDef.variants != null) {
            for (OsDefinition.Variant v : currentDef.variants) {
                variantChoice.addItem(v.name != null ? v.name : v.id);
            }
        }
        refreshOkButton();
    }

    /** Enables the OK button only when all four fields are filled. */
    private void refreshOkButton() {
        boolean ready = !nameField.getText().trim().isEmpty()
                     && !dirField.getText().trim().isEmpty()
                     && !selectedText(osChoice).isEmpty()
                     && !selectedText(variantChoice).isEmpty();
        okBtn.setEnabled(ready);
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    /** Returns the selected item of a {@link Choice}, or {@code ""} if blank/none. */
    private static String selectedText(Choice c) {
        String s = c.getSelectedItem();
        return s == null ? "" : s.trim();
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