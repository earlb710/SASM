package com.sasm;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;

/**
 * "Add Variant" dialog — collects a variant name and all target-platform
 * settings (Operating System, Output Type, Format Variant, Processor).
 *
 * <p>Five rows are stacked vertically on a scrollable canvas:</p>
 * <ol>
 *   <li>Variant Name — free-text field</li>
 *   <li>Operating System — pop-list (blank default; Linux / Windows) followed by
 *       a read-only description area showing the format name and overview</li>
 *   <li>Output Type — pop-list (Executable or DLL / Shared Library) populated
 *       when the OS is chosen</li>
 *   <li>Variant — pop-list (populated when Output Type changes) followed by a
 *       read-only description area with architecture, toolchain, etc.</li>
 *   <li>Processor — pop-list (filtered by the variant's architecture) followed
 *       by a read-only description area with historical context</li>
 * </ol>
 * <p>OK and Cancel buttons appear at the bottom.  OK is enabled only when all
 * five fields contain a non-blank value and the name is valid.</p>
 */
public class AddVariantWizard extends JDialog {

    /** Regex that every valid variant name must fully match. */
    private static final String VARIANT_NAME_PATTERN = "[A-Za-z0-9_\\-]+";

    // ── form fields ──────────────────────────────────────────────────────────
    private final JTextField          variantNameField = new JTextField(50);
    private final JComboBox<String>   osChoice         = new JComboBox<>();
    private final JComboBox<String>   outputTypeChoice = new JComboBox<>();
    private final JComboBox<String>   variantChoice    = new JComboBox<>();
    private final JComboBox<String>   processorChoice  = new JComboBox<>();

    // ── description panels ───────────────────────────────────────────────────
    private final JTextArea osDescArea        = makeDescArea(3);
    private final JTextArea variantDescArea   = makeDescArea(7);
    private final JTextArea processorDescArea = makeDescArea(5);

    // ── buttons ──────────────────────────────────────────────────────────────
    private final JButton okBtn     = new JButton("OK");
    private final JButton cancelBtn = new JButton("Cancel");

    // ── result state ─────────────────────────────────────────────────────────
    private boolean confirmed = false;

    // ── OS data ──────────────────────────────────────────────────────────────
    private OsDefinition currentDef;

    /** Existing variant data to pre-fill when editing properties, or null. */
    private ProjectFile.VariantEntry preFill;

    public AddVariantWizard(Frame owner) {
        this(owner, null);
    }

    /**
     * Creates the wizard optionally pre-filled with existing variant data.
     * When {@code existing} is non-null the dialog title changes to
     * "Variant Properties" and the fields are seeded from the existing variant.
     */
    public AddVariantWizard(Frame owner, ProjectFile.VariantEntry existing) {
        super(owner, existing != null ? "Variant Properties" : "Add Variant",
                true /* modal */);
        this.preFill = existing;
        buildUi();
        if (existing != null) {
            applyPreFill(existing);
        }
        pack();
        setResizable(true);
        setMinimumSize(new Dimension(900, 550));
        setLocationRelativeTo(owner);
    }

    // ── public accessors ─────────────────────────────────────────────────────

    public boolean isConfirmed()         { return confirmed; }
    public String getVariantName()       { return variantNameField.getText().trim(); }
    public String getSelectedOs()        { return selectedText(osChoice); }
    public String getSelectedOutputType(){ return selectedText(outputTypeChoice); }
    public String getSelectedVariant()   { return selectedText(variantChoice); }
    public String getSelectedProcessor() { return selectedText(processorChoice); }

    /**
     * Builds and returns a {@link ProjectFile.VariantEntry} from the current
     * form values.
     */
    public ProjectFile.VariantEntry toVariantEntry() {
        ProjectFile.VariantEntry ve = new ProjectFile.VariantEntry();
        ve.variantName = getVariantName();
        ve.os          = getSelectedOs();
        ve.outputType  = getSelectedOutputType();
        ve.variant     = getSelectedVariant();
        ve.processor   = getSelectedProcessor();
        return ve;
    }

    // ────────────────────────────────────────────────────────────────────────
    // UI construction
    // ────────────────────────────────────────────────────────────────────────

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));

        // ── title banner ────────────────────────────────────────────────────
        JLabel title = new JLabel(
                preFill != null ? "Variant Properties" : "Add Variant",
                SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setBackground(new Color(0x2B, 0x57, 0x97));
        title.setForeground(Color.WHITE);
        title.setOpaque(true);
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.add(title, BorderLayout.CENTER);
        titlePanel.setPreferredSize(new Dimension(900, 36));
        add(titlePanel, BorderLayout.NORTH);

        // ── scrollable canvas ────────────────────────────────────────────────
        JPanel canvas = new JPanel(new GridBagLayout());
        canvas.setBackground(Color.WHITE);

        int gbRow = 0;

        // ── Variant Name ─────────────────────────────────────────────────────
        gbRow = addLabeledControl(canvas, gbRow, "Variant Name:", variantNameField,
                "A label for this variant (e.g. linux-64-static, win-dll-32).");

        // ── Operating System ──────────────────────────────────────────────────
        osChoice.addItem("");
        osChoice.addItem("Linux");
        osChoice.addItem("Windows");
        gbRow = addLabeledControl(canvas, gbRow, "Operating System:", osChoice,
                "Select the target OS to load matching format definitions.");
        gbRow = addDescriptionArea(canvas, gbRow, new JScrollPane(osDescArea));

        // ── Output Type ──────────────────────────────────────────────────────
        outputTypeChoice.addItem("");
        gbRow = addLabeledControl(canvas, gbRow, "Output Type:", outputTypeChoice,
                "Select the output type: Executable or DLL / Shared Library.");

        // ── Variant ───────────────────────────────────────────────────────────
        variantChoice.addItem("");
        gbRow = addLabeledControl(canvas, gbRow, "Variant:", variantChoice,
                "Select the format variant after choosing an operating system and output type.");
        gbRow = addDescriptionArea(canvas, gbRow, new JScrollPane(variantDescArea));

        // ── Processor ─────────────────────────────────────────────────────────
        processorChoice.addItem("");
        gbRow = addLabeledControl(canvas, gbRow, "Processor:", processorChoice,
                "Select the target CPU; choices are filtered to processors compatible with the"
                + " selected variant's architecture (e.g. x86 family for x86/x86-64 variants).");
        gbRow = addDescriptionArea(canvas, gbRow, new JScrollPane(processorDescArea));

        // spacer
        GridBagConstraints sp = new GridBagConstraints();
        sp.gridx = 0; sp.gridy = gbRow; sp.gridwidth = 2;
        sp.fill = GridBagConstraints.BOTH;
        sp.weighty = 1.0;
        canvas.add(new JPanel(), sp);

        JScrollPane scrollPane = new JScrollPane(canvas);
        scrollPane.setPreferredSize(new Dimension(900, 680));
        add(scrollPane, BorderLayout.CENTER);

        // ── button row ──────────────────────────────────────────────────────
        okBtn.setEnabled(false);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        btnRow.add(okBtn);
        btnRow.add(cancelBtn);
        add(btnRow, BorderLayout.SOUTH);

        // ── wire events ─────────────────────────────────────────────────────
        osChoice.addItemListener(e -> onOsChanged());
        outputTypeChoice.addItemListener(e -> onOutputTypeChanged());
        variantChoice.addItemListener(e -> onVariantChanged());
        processorChoice.addItemListener(e -> onProcessorChanged());
        variantNameField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshOkButton(); }
            public void removeUpdate(DocumentEvent e) { refreshOkButton(); }
            public void changedUpdate(DocumentEvent e) { refreshOkButton(); }
        });
        okBtn.addActionListener(e -> onOkPressed());
        cancelBtn.addActionListener(e -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });

        refreshOkButton();
    }

    // ── layout helpers ────────────────────────────────────────────────────────

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

    private static int addDescriptionArea(JPanel canvas, int startRow, Component area) {
        GridBagConstraints dc = new GridBagConstraints();
        dc.gridx = 0; dc.gridy = startRow; dc.gridwidth = 2;
        dc.fill = GridBagConstraints.BOTH;
        dc.weightx = 1.0;
        dc.anchor = GridBagConstraints.NORTHWEST;
        dc.insets = new Insets(2, 16, 8, 16);
        canvas.add(area, dc);
        return startRow + 1;
    }

    private static JTextArea makeDescArea(int rows) {
        JTextArea ta = new JTextArea("", rows, 60);
        ta.setEditable(false);
        ta.setFont(new Font("Monospaced", Font.PLAIN, 11));
        ta.setBackground(new Color(0xF0, 0xF4, 0xFF));
        ta.setForeground(new Color(0x1A, 0x1A, 0x2E));
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        return ta;
    }

    // ── event handlers ────────────────────────────────────────────────────────

    private void onOsChanged() {
        currentDef = null;
        processorDescArea.setText("");
        processorChoice.removeAllItems();
        processorChoice.addItem("");
        variantDescArea.setText("");
        variantChoice.removeAllItems();
        variantChoice.addItem("");
        outputTypeChoice.removeAllItems();
        outputTypeChoice.addItem("");

        String os = selectedText(osChoice);
        if (os.isEmpty()) {
            osDescArea.setText("");
            refreshOkButton();
            return;
        }

        outputTypeChoice.addItem("Executable");
        outputTypeChoice.addItem("DLL / Shared Library");

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

    private void onOutputTypeChanged() {
        currentDef = null;
        processorDescArea.setText("");
        processorChoice.removeAllItems();
        processorChoice.addItem("");
        variantDescArea.setText("");
        variantChoice.removeAllItems();
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

        osDescArea.setText(buildOsDescription(currentDef));
        osDescArea.setCaretPosition(0);

        if (currentDef.variants != null) {
            for (OsDefinition.Variant v : currentDef.variants) {
                variantChoice.addItem(v.name != null ? v.name : v.id);
            }
        }
        refreshOkButton();
    }

    private void onVariantChanged() {
        processorDescArea.setText("");
        processorChoice.removeAllItems();
        processorChoice.addItem("");

        String selected = selectedText(variantChoice);
        if (selected.isEmpty() || currentDef == null || currentDef.variants == null) {
            variantDescArea.setText("");
            refreshOkButton();
            return;
        }

        int idx = variantChoice.getSelectedIndex();
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

        String[] processors = processorsForArchitecture(v.architecture);
        for (String p : processors) {
            processorChoice.addItem(p);
        }

        // Default to x86_64 if available
        for (int pi = 0; pi < processorChoice.getItemCount(); pi++) {
            if ("x86_64".equals(processorChoice.getItemAt(pi))) {
                processorChoice.setSelectedIndex(pi);
                onProcessorChanged();
                break;
            }
        }

        refreshOkButton();
    }

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

    /** Enables OK only when all five fields are non-blank and the name is valid. */
    private void refreshOkButton() {
        String name = variantNameField.getText().trim();
        boolean nameOk = !name.isEmpty() && name.matches(VARIANT_NAME_PATTERN);
        boolean ready = nameOk
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

        if (v.architecture != null) sb.append("Architecture : ").append(v.architecture).append('\n');
        if (v.bits > 0)             sb.append("Bits         : ").append(v.bits).append('\n');
        if (v.linking != null)      sb.append("Linking      : ").append(v.linking).append('\n');

        if (v.description != null && !v.description.isBlank()) {
            sb.append('\n');
            sb.append(wrap(v.description, 80)).append('\n');
        }

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

    private static String[] processorsForArchitecture(String architecture) {
        if (architecture == null) return new String[0];
        return switch (architecture.toLowerCase()) {
            case "x86_64" -> new String[]{
                "8086", "80186", "80286", "80386", "80486", "pentium", "x86_64"
            };
            case "x86" -> new String[]{
                "8086", "80186", "80286", "80386", "80486", "pentium"
            };
            default -> new String[0];
        };
    }

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

    private static String selectedText(JComboBox<String> c) {
        String s = (String) c.getSelectedItem();
        return s == null ? "" : s.trim();
    }

    /**
     * Selects the item in a {@link JComboBox} whose text equals {@code value},
     * returning {@code true} if found.
     */
    private static boolean selectItem(JComboBox<String> c, String value) {
        if (value == null) return false;
        for (int i = 0; i < c.getItemCount(); i++) {
            if (value.equals(c.getItemAt(i))) {
                c.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    /**
     * Pre-fills all form fields from an existing {@link ProjectFile.VariantEntry}
     * by programmatically selecting each Choice value and firing the cascade.
     */
    private void applyPreFill(ProjectFile.VariantEntry ve) {
        if (ve.variantName != null) variantNameField.setText(ve.variantName);

        // Select OS → triggers onOsChanged
        if (ve.os != null && selectItem(osChoice, ve.os)) {
            onOsChanged();
        }

        // Select Output Type → triggers onOutputTypeChanged
        if (ve.outputType != null && selectItem(outputTypeChoice, ve.outputType)) {
            onOutputTypeChanged();
        }

        // Select Variant → triggers onVariantChanged (which populates processors)
        if (ve.variant != null && selectItem(variantChoice, ve.variant)) {
            onVariantChanged();
        }

        // Select Processor → triggers onProcessorChanged
        if (ve.processor != null) selectItem(processorChoice, ve.processor);
        onProcessorChanged();

        refreshOkButton();
    }

    private void onOkPressed() {
        String name = variantNameField.getText().trim();
        if (!name.matches(VARIANT_NAME_PATTERN)) {
            showError("Variant name may only contain letters, digits,\n"
                    + "underscores (_) and hyphens (-).\n"
                    + "Spaces and other special characters are not allowed.");
            return;
        }
        confirmed = true;
        dispose();
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
