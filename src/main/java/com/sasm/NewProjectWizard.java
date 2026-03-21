package com.sasm;

import java.awt.*;
import java.awt.event.*;

/**
 * "New Project" dialog — single flat-form canvas with inline descriptions.
 *
 * <p>Six rows are stacked vertically on a scrollable canvas:</p>
 * <ol>
 *   <li>Name — free-text field</li>
 *   <li>Working Directory — free-text field with a folder-browse button
 *       (pre-filled with the user's home directory)</li>
 *   <li>Operating System — pop-list (blank default; Linux / Windows) followed by
 *       a read-only description area showing the format name and overview</li>
 *   <li>Output Type — pop-list (Executable or DLL / Shared Library) populated when
 *       OS is chosen; selects the format definition file loaded for variants</li>
 *   <li>Variant — pop-list (blank default; populated when Output Type changes)
 *       followed by a read-only description area showing architecture, linking
 *       style, toolchain commands, and the full variant description</li>
 *   <li>Processor — pop-list (blank default; populated with x86-family processors
 *       that are compatible with the selected variant's architecture) followed by
 *       a read-only description area with historical context and compatibility notes</li>
 * </ol>
 * <p>OK and Cancel buttons appear at the bottom.  OK is enabled only when all
 * six fields contain a non-blank value.</p>
 */
public class NewProjectWizard extends Dialog {

    /** Regex that every valid project name must fully match. */
    private static final String PROJECT_NAME_PATTERN = "[A-Za-z0-9_\\-]+";

    // ── form fields ──────────────────────────────────────────────────────────
    private final TextField nameField      = new TextField(50);
    private final TextField dirField       = new TextField(50);
    private final Button    browseBtn      = new Button("Browse…");
    private final Choice    osChoice         = new Choice();
    private final Choice    outputTypeChoice = new Choice();
    private final Choice    variantChoice    = new Choice();
    private final Choice    processorChoice  = new Choice();

    // ── description panels ───────────────────────────────────────────────────
    private final TextArea osDescArea        = makeDescArea(3);
    private final TextArea variantDescArea   = makeDescArea(7);
    private final TextArea processorDescArea = makeDescArea(5);

    // ── buttons ──────────────────────────────────────────────────────────────
    private final Button okBtn     = new Button("OK");
    private final Button cancelBtn = new Button("Cancel");

    // ── result state (read by caller after dispose) ───────────────────────────
    private boolean confirmed = false;
    /** Path of the {@code <name>.json} file written when the user clicks OK. */
    private java.io.File savedProjectFile;

    // ── OS data ──────────────────────────────────────────────────────────────
    private OsDefinition currentDef;

    public NewProjectWizard(Frame owner) {
        super(owner, "New Project", true /* modal */);
        buildUi();
        pack();
        setResizable(true);
        setMinimumSize(new Dimension(900, 550));
        setLocationRelativeTo(owner);
    }

    // ── public accessors (usable by the caller after OK is pressed) ───────────

    /** Returns {@code true} if the user pressed OK, {@code false} for Cancel. */
    public boolean isConfirmed() { return confirmed; }

    public String getProjectName()      { return nameField.getText().trim(); }
    public String getWorkingDirectory() { return dirField.getText().trim(); }
    public String getSelectedOs()       { return selectedText(osChoice); }
    public String getSelectedOutputType(){ return selectedText(outputTypeChoice); }
    public String getSelectedVariant()  { return selectedText(variantChoice); }
    public String getSelectedProcessor(){ return selectedText(processorChoice); }
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
        titlePanel.setPreferredSize(new Dimension(900, 36));
        add(titlePanel, BorderLayout.NORTH);

        // ── scrollable canvas ────────────────────────────────────────────────
        Panel canvas = new Panel(new GridBagLayout());
        canvas.setBackground(Color.WHITE);

        int gbRow = 0;   // running GridBag row counter

        // ── Name ─────────────────────────────────────────────────────────────
        gbRow = addLabeledControl(canvas, gbRow, "Name:", nameField,
                "The project name is used as the source-file prefix (e.g. hello → hello.sasm).");

        // ── Working Directory ─────────────────────────────────────────────────
        // Pre-populate with the user's home directory
        dirField.setText(System.getProperty("user.home", ""));

        Panel dirPanel = new Panel(new BorderLayout(4, 0));
        dirPanel.setBackground(Color.WHITE);
        dirPanel.add(dirField, BorderLayout.CENTER);
        dirPanel.add(browseBtn, BorderLayout.EAST);
        gbRow = addLabeledControl(canvas, gbRow, "Working Directory:", dirPanel,
                "All generated files (.sasm, .o, binary) will be written into this folder.");

        // ── Operating System ──────────────────────────────────────────────────
        osChoice.addItem("");          // blank default
        osChoice.addItem("Linux");
        osChoice.addItem("Windows");
        gbRow = addLabeledControl(canvas, gbRow, "Operating System:", osChoice,
                "Select the target OS to load the matching format definitions.");
        // OS description area (full-width, initially hidden)
        gbRow = addDescriptionArea(canvas, gbRow, osDescArea);

        // ── Output Type ──────────────────────────────────────────────────────
        outputTypeChoice.addItem("");  // blank default
        gbRow = addLabeledControl(canvas, gbRow, "Output Type:", outputTypeChoice,
                "Select the output type: Executable or DLL / Shared Library.");

        // ── Variant ───────────────────────────────────────────────────────────
        variantChoice.addItem("");     // blank default
        gbRow = addLabeledControl(canvas, gbRow, "Variant:", variantChoice,
                "Select the format variant after choosing an operating system and output type.");
        // Variant description area (full-width, initially empty)
        gbRow = addDescriptionArea(canvas, gbRow, variantDescArea);

        // ── Processor ─────────────────────────────────────────────────────────
        processorChoice.addItem("");   // blank default
        gbRow = addLabeledControl(canvas, gbRow, "Processor:", processorChoice,
                "Select the target CPU; choices are filtered to processors compatible with the"
                + " selected variant's architecture (e.g. x86 family for x86/x86-64 variants).");
        // Processor description area (full-width, initially empty)
        gbRow = addDescriptionArea(canvas, gbRow, processorDescArea);

        // spacer row keeps content pinned to top
        GridBagConstraints sp = new GridBagConstraints();
        sp.gridx = 0; sp.gridy = gbRow; sp.gridwidth = 2;
        sp.fill = GridBagConstraints.BOTH;
        sp.weighty = 1.0;
        canvas.add(new Panel(), sp);

        // Wrap canvas in a scroll pane so the dialog stays manageable
        ScrollPane scrollPane = new ScrollPane(ScrollPane.SCROLLBARS_AS_NEEDED);
        scrollPane.add(canvas);
        scrollPane.setPreferredSize(new Dimension(900, 680));
        add(scrollPane, BorderLayout.CENTER);

        // ── button row ──────────────────────────────────────────────────────
        okBtn.setEnabled(false);
        Panel btnRow = new Panel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        btnRow.add(okBtn);
        btnRow.add(cancelBtn);
        add(btnRow, BorderLayout.SOUTH);

        // ── wire events ─────────────────────────────────────────────────────
        browseBtn.addActionListener(e -> browseForDirectory());
        osChoice.addItemListener(e -> onOsChanged());
        outputTypeChoice.addItemListener(e -> onOutputTypeChanged());
        variantChoice.addItemListener(e -> onVariantChanged());
        processorChoice.addItemListener(e -> onProcessorChanged());
        nameField.addTextListener(e -> refreshOkButton());
        dirField.addTextListener(e -> refreshOkButton());
        okBtn.addActionListener(e -> onOkPressed());
        cancelBtn.addActionListener(e -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });

        // Trigger OK-button state for the pre-filled directory
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
        // Label column (col 0)
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = startRow;
        lc.anchor = GridBagConstraints.NORTHWEST;
        lc.insets = new Insets(startRow == 0 ? 16 : 12, 16, 2, 8);
        Label lbl = new Label(labelText, Label.RIGHT);
        lbl.setFont(new Font("SansSerif", Font.BOLD, 12));
        canvas.add(lbl, lc);

        // Control column (col 1)
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

    /**
     * Appends a full-width read-only description {@link TextArea} to {@code canvas}
     * (spanning both label and control columns) and returns the next GridBag row.
     * The area is initially empty.
     */
    private static int addDescriptionArea(Panel canvas, int startRow, TextArea area) {
        GridBagConstraints dc = new GridBagConstraints();
        dc.gridx = 0; dc.gridy = startRow; dc.gridwidth = 2;
        dc.fill = GridBagConstraints.BOTH;
        dc.weightx = 1.0;
        dc.anchor = GridBagConstraints.NORTHWEST;
        dc.insets = new Insets(2, 16, 8, 16);
        canvas.add(area, dc);
        return startRow + 1;
    }

    /** Creates a styled read-only description {@link TextArea}. */
    private static TextArea makeDescArea(int rows) {
        TextArea ta = new TextArea("", rows, 60, TextArea.SCROLLBARS_VERTICAL_ONLY);
        ta.setEditable(false);
        ta.setFont(new Font("Monospaced", Font.PLAIN, 11));
        ta.setBackground(new Color(0xF0, 0xF4, 0xFF));
        ta.setForeground(new Color(0x1A, 0x1A, 0x2E));
        return ta;
    }

    // ── event handlers ────────────────────────────────────────────────────────

    /** Opens a FileDialog to let the user navigate to a directory. */
    private void browseForDirectory() {
        FileDialog fd = new FileDialog(this, "Select Working Directory", FileDialog.LOAD);
        // On macOS this property switches to a folder-picker mode.
        System.setProperty("apple.awt.fileDialogForDirectories", "true");
        fd.setVisible(true);
        System.setProperty("apple.awt.fileDialogForDirectories", "false");

        String dir = fd.getDirectory();
        if (dir != null && !dir.isEmpty()) {
            // FileDialog.getDirectory() already returns the parent directory path.
            dirField.setText(dir);
            refreshOkButton();
        }
    }

    /** Called when the OS pop-list changes — reloads output types and updates OS description. */
    private void onOsChanged() {
        // Reset all downstream state
        currentDef = null;
        processorDescArea.setText("");
        processorChoice.removeAll();
        processorChoice.addItem("");
        variantDescArea.setText("");
        variantChoice.removeAll();
        variantChoice.addItem("");
        outputTypeChoice.removeAll();
        outputTypeChoice.addItem("");

        String os = selectedText(osChoice);
        if (os.isEmpty()) {
            osDescArea.setText("");
            refreshOkButton();
            return;
        }

        // Populate output-type choices per OS
        outputTypeChoice.addItem("Executable");
        if (os.equalsIgnoreCase("Windows")) {
            outputTypeChoice.addItem("DLL / Shared Library");
        } else if (os.equalsIgnoreCase("Linux")) {
            outputTypeChoice.addItem("DLL / Shared Library");
        }

        // Load the default (Executable) definition to show the OS description
        try {
            currentDef = JsonLoader.load(os);
        } catch (Exception ex) {
            osDescArea.setText("Could not load definition for " + os + ":\n" + ex.getMessage());
            refreshOkButton();
            return;
        }

        osDescArea.setText(buildOsDescription(currentDef));
        osDescArea.setCaretPosition(0);
        refreshOkButton();
    }

    /** Called when the Output Type pop-list changes — reloads variants. */
    private void onOutputTypeChanged() {
        // Reset downstream state
        currentDef = null;
        processorDescArea.setText("");
        processorChoice.removeAll();
        processorChoice.addItem("");
        variantDescArea.setText("");
        variantChoice.removeAll();
        variantChoice.addItem("");

        String os = selectedText(osChoice);
        String outputType = selectedText(outputTypeChoice);
        if (os.isEmpty() || outputType.isEmpty()) {
            refreshOkButton();
            return;
        }

        try {
            currentDef = JsonLoader.load(os, outputType);
        } catch (Exception ex) {
            osDescArea.setText("Could not load definition for " + os
                    + " (" + outputType + "):\n" + ex.getMessage());
            refreshOkButton();
            return;
        }

        // Update OS description area to reflect the loaded definition
        osDescArea.setText(buildOsDescription(currentDef));
        osDescArea.setCaretPosition(0);

        // Populate variant choices
        if (currentDef.variants != null) {
            for (OsDefinition.Variant v : currentDef.variants) {
                variantChoice.addItem(v.name != null ? v.name : v.id);
            }
        }
        refreshOkButton();
    }

    /** Called when the Variant pop-list changes — updates variant description and processor list. */
    private void onVariantChanged() {
        // Reset processor state whenever the variant changes
        processorDescArea.setText("");
        processorChoice.removeAll();
        processorChoice.addItem("");

        String selected = selectedText(variantChoice);
        if (selected.isEmpty() || currentDef == null || currentDef.variants == null) {
            variantDescArea.setText("");
            refreshOkButton();
            return;
        }

        int idx = variantChoice.getSelectedIndex();
        // The variant Choice always has a blank item at index 0, followed by the
        // real variants from currentDef.variants.  idx > 0 means a real variant
        // is selected, and variantIdx maps it to the variants list (0-based).
        if (idx <= 0) {
            variantDescArea.setText("");
            refreshOkButton();
            return;
        }
        int variantIdx = idx - 1;

        if (variantIdx < 0 || variantIdx >= currentDef.variants.size()) {
            variantDescArea.setText("");
            refreshOkButton();
            return;
        }

        OsDefinition.Variant v = currentDef.variants.get(variantIdx);
        variantDescArea.setText(buildVariantDescription(v));
        variantDescArea.setCaretPosition(0);

        // Populate processors compatible with this variant's architecture
        for (String p : processorsForArchitecture(v.architecture)) {
            processorChoice.addItem(p);
        }

        refreshOkButton();
    }

    /** Called when the Processor pop-list changes — updates the processor description. */
    private void onProcessorChanged() {
        String selected = selectedText(processorChoice);
        if (selected.isEmpty()) {
            processorDescArea.setText("");
            refreshOkButton();
            return;
        }

        try {
            ProcessorDefinition def = JsonLoader.loadProcessor(selected);
            processorDescArea.setText(buildProcessorDescription(def));
            processorDescArea.setCaretPosition(0);
        } catch (Exception ex) {
            processorDescArea.setText(
                    "Could not load processor definition for '" + selected + "':\n"
                    + ex.getMessage());
        }
        refreshOkButton();
    }

    /** Enables OK only when all six fields are non-blank and the name is valid. */
    private void refreshOkButton() {
        String name = nameField.getText().trim();
        boolean nameOk = !name.isEmpty() && name.matches(PROJECT_NAME_PATTERN);
        boolean ready = nameOk
                     && !dirField.getText().trim().isEmpty()
                     && !selectedText(osChoice).isEmpty()
                     && !selectedText(outputTypeChoice).isEmpty()
                     && !selectedText(variantChoice).isEmpty()
                     && !selectedText(processorChoice).isEmpty();
        okBtn.setEnabled(ready);
    }

    // ── description builders ──────────────────────────────────────────────────

    private static String buildOsDescription(OsDefinition def) {
        StringBuilder sb = new StringBuilder();
        if (def.format_full_name != null) {
            sb.append("Format : ").append(def.format_full_name).append('\n');
        }
        if (def.format != null) {
            sb.append("Short  : ").append(def.format).append('\n');
        }
        if (def.description != null && !def.description.isBlank()) {
            sb.append('\n').append(wrap(def.description, 80)).append('\n');
        }
        return sb.toString();
    }

    private static String buildVariantDescription(OsDefinition.Variant v) {
        StringBuilder sb = new StringBuilder();

        // ── summary line ────────────────────────────────────────────────────
        if (v.architecture != null) sb.append("Architecture : ").append(v.architecture).append('\n');
        if (v.bits > 0)             sb.append("Bits         : ").append(v.bits).append('\n');
        if (v.linking != null)      sb.append("Linking      : ").append(v.linking).append('\n');

        // ── description ─────────────────────────────────────────────────────
        if (v.description != null && !v.description.isBlank()) {
            sb.append('\n');
            sb.append(wrap(v.description, 80)).append('\n');
        }

        // ── toolchain commands ───────────────────────────────────────────────
        if (v.toolchain != null) {
            sb.append('\n');
            sb.append("─── Toolchain ").append("─".repeat(66)).append('\n');
            if (v.toolchain.assemble != null)
                sb.append("Assemble        : ").append(v.toolchain.assemble).append('\n');
            if (v.toolchain.link != null)
                sb.append("Link            : ").append(v.toolchain.link).append('\n');
            if (v.toolchain.assemble_and_link != null)
                sb.append("Assemble + Link : ").append(v.toolchain.assemble_and_link).append('\n');
        }

        // ── required components summary ──────────────────────────────────────
        if (v.required_components != null && !v.required_components.isEmpty()) {
            sb.append('\n');
            sb.append("─── Required Components (").append(v.required_components.size())
              .append(") ").append("─".repeat(50)).append('\n');
            for (OsDefinition.Component c : v.required_components) {
                String marker = c.required ? "  [required]" : "  [optional]";
                sb.append("  • ").append(c.name != null ? c.name : "?").append(marker);
                if (c.size_bytes != null) sb.append("  size=").append(c.size_bytes);
                sb.append('\n');
                if (c.description != null && !c.description.isBlank()) {
                    sb.append("    ").append(wrap(c.description, 76).replace("\n", "\n    "))
                      .append('\n');
                }
            }
        }

        return sb.toString();
    }

    private static String buildProcessorDescription(ProcessorDefinition def) {
        StringBuilder sb = new StringBuilder();
        if (def.processor != null) {
            sb.append("Processor : ").append(def.processor).append('\n');
        }
        if (def.source_reference != null) {
            sb.append("Reference : ").append(def.source_reference).append('\n');
        }
        if (def.description != null && !def.description.isBlank()) {
            sb.append('\n').append(wrap(def.description, 80)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Returns the ordered list of processor IDs that are compatible with
     * the given architecture string.  The list is cumulative — each later
     * processor is a superset of the earlier one's instruction set.
     *
     * @param architecture variant architecture string (e.g. {@code "x86"},
     *                     {@code "x86_64"})
     * @return array of processor IDs in historical/cumulative order;
     *         empty array for unrecognised architectures
     */
    private static String[] processorsForArchitecture(String architecture) {
        if (architecture == null) return new String[0];
        return switch (architecture.toLowerCase()) {
            // 64-bit long mode: full cumulative x86 chain
            case "x86_64" -> new String[]{
                "8086", "80186", "80286", "80386", "80486", "pentium", "x86_64"
            };
            // 32-bit protected mode: all pre-64-bit x86 processors
            case "x86" -> new String[]{
                "8086", "80186", "80286", "80386", "80486", "pentium"
            };
            default -> new String[0];
        };
    }

    /**
     * Wraps {@code text} at approximately {@code maxWidth} characters, breaking
     * only on space boundaries.
     */
    private static String wrap(String text, int maxWidth) {
        if (text == null) return "";
        String[] words = text.split("\\s+");
        StringBuilder out = new StringBuilder();
        int col = 0;
        for (String word : words) {
            if (col > 0 && col + 1 + word.length() > maxWidth) {
                out.append('\n');
                col = 0;
            } else if (col > 0) {
                out.append(' ');
                col++;
            }
            out.append(word);
            col += word.length();
        }
        return out.toString();
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    /** Returns the selected item of a {@link Choice}, or {@code ""} if blank/none. */
    private static String selectedText(Choice c) {
        String s = c.getSelectedItem();
        return s == null ? "" : s.trim();
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
     * Writes all current form values to {@code <workingDirectory>/<name>.json}.
     *
     * @return the file that was written
     * @throws java.io.IOException if the directory cannot be created or the file
     *                             cannot be written
     */
    private java.io.File saveProject(String name) throws java.io.IOException {
        ProjectFile pf = new ProjectFile();
        pf.name             = name;
        pf.workingDirectory = dirField.getText().trim();
        pf.os               = selectedText(osChoice);
        pf.outputType       = selectedText(outputTypeChoice);
        pf.variant          = selectedText(variantChoice);
        pf.processor        = selectedText(processorChoice);

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