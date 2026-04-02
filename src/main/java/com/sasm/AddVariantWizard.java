package com.sasm;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;

/**
 * "Add Variant" dialog — collects a variant name and all target-platform
 * settings (Processor, Operating System, Output Type, Format Variant).
 *
 * <p>Five rows are stacked vertically on a scrollable canvas:</p>
 * <ol>
 *   <li>Variant Name — free-text field</li>
 *   <li>Processor — pop-list (all processors, highest selected by default)
 *       followed by a read-only description area with historical context</li>
 *   <li>Operating System — pop-list filtered by the selected processor's
 *       compatible architectures, followed by a read-only description area</li>
 *   <li>Output Type — pop-list (Executable or DLL / Shared Library) filtered
 *       to only show types that have variants matching the processor</li>
 *   <li>Variant — pop-list filtered by processor / OS / output type, followed
 *       by a read-only description area with architecture, toolchain, etc.</li>
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

    /** True once the user has manually typed into the variant name field. */
    private boolean userEditedName = false;

    /** Suppresses the document listener when the name is set programmatically. */
    private boolean settingNameProgrammatically = false;

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

        // ── Processor (first, with highest selected) ─────────────────────────
        for (String p : ALL_PROCESSORS) {
            processorChoice.addItem(p);
        }
        processorChoice.setSelectedItem(DEFAULT_PROCESSOR);
        gbRow = addLabeledControl(canvas, gbRow, "Processor:", processorChoice,
                "Select the target CPU. The operating system and variant choices below"
                + " will be filtered to match the selected processor's architecture.");
        gbRow = addDescriptionArea(canvas, gbRow, new JScrollPane(processorDescArea));

        // ── Operating System (filtered by processor) ─────────────────────────
        osChoice.addItem("");
        gbRow = addLabeledControl(canvas, gbRow, "Operating System:", osChoice,
                "Select the target OS (filtered to OSes that support the selected processor).");
        gbRow = addDescriptionArea(canvas, gbRow, new JScrollPane(osDescArea));

        // ── Output Type ──────────────────────────────────────────────────────
        outputTypeChoice.addItem("");
        gbRow = addLabeledControl(canvas, gbRow, "Output Type:", outputTypeChoice,
                "Select the output type: Executable or DLL / Shared Library.");

        // ── Variant (filtered by processor / OS / output type) ───────────────
        variantChoice.addItem("");
        gbRow = addLabeledControl(canvas, gbRow, "Variant:", variantChoice,
                "Select the format variant (filtered by processor, OS, and output type).");
        gbRow = addDescriptionArea(canvas, gbRow, new JScrollPane(variantDescArea));

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
        processorChoice.addItemListener(e -> onProcessorChanged());
        osChoice.addItemListener(e -> onOsChanged());
        outputTypeChoice.addItemListener(e -> onOutputTypeChanged());
        variantChoice.addItemListener(e -> onVariantChanged());
        variantNameField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onNameFieldChanged(); }
            public void removeUpdate(DocumentEvent e) { onNameFieldChanged(); }
            public void changedUpdate(DocumentEvent e) { onNameFieldChanged(); }
            private void onNameFieldChanged() {
                if (!settingNameProgrammatically) {
                    // Reset if user clears the field so auto-population can resume
                    userEditedName = !variantNameField.getText().trim().isEmpty();
                }
                refreshOkButton();
            }
        });
        okBtn.addActionListener(e -> onOkPressed());
        cancelBtn.addActionListener(e -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });

        // fire initial processor selection to populate OS choices
        onProcessorChanged();
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

    // ── processor / architecture constants ──────────────────────────────────

    /**
     * All available processors ordered from highest (most recent / most
     * powerful) to lowest, grouped by architecture family.
     * The first entry is the default selection.
     */
    private static final String[] ALL_PROCESSORS = {
        "x86_64", "pentium", "80486", "80386", "80286", "80186", "8086",
        "aarch64", "armv7"
    };

    /** The processor pre-selected when the dialog opens. */
    private static final String DEFAULT_PROCESSOR = "x86_64";

    /** All known operating systems. */
    private static final String[] ALL_OSES = { "Linux", "Windows" };

    /** All known output types. */
    private static final String[] ALL_OUTPUT_TYPES = {
        "Executable", "DLL / Shared Library"
    };

    /**
     * Returns the set of variant architectures compatible with the given
     * processor.  For example, all 32-bit x86 processors (8086–pentium)
     * can target both {@code "x86"} and {@code "x86_64"} variants (the
     * 64-bit host can run 32-bit code), while the {@code "x86_64"} processor
     * only targets 64-bit variants.
     */
    private static Set<String> architecturesForProcessor(String processor) {
        if (processor == null || processor.isEmpty()) return Set.of();
        return switch (processor.toLowerCase()) {
            case "x86_64" -> Set.of("x86_64");
            case "pentium", "80486", "80386", "80286", "80186", "8086"
                           -> Set.of("x86", "x86_64");
            case "aarch64" -> Set.of("aarch64");
            case "armv7"   -> Set.of("arm32");
            default        -> Set.of();
        };
    }

    /**
     * Returns the set of OSes that have at least one variant whose
     * architecture is compatible with the given processor.  Checks all
     * output types for each OS.
     */
    private static Set<String> osesForProcessor(String processor) {
        Set<String> archs = architecturesForProcessor(processor);
        if (archs.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String os : ALL_OSES) {
            for (String ot : ALL_OUTPUT_TYPES) {
                try {
                    OsDefinition def = JsonLoader.load(os, ot);
                    if (def.variants != null) {
                        for (OsDefinition.Variant v : def.variants) {
                            if (v.architecture != null
                                    && archs.contains(v.architecture.toLowerCase())) {
                                result.add(os);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) { /* no definition for this combo */ }
                if (result.contains(os)) break; // already found a match
            }
        }
        return result;
    }

    /**
     * Returns the output types that have at least one variant compatible
     * with the given processor for the given OS.
     */
    private static List<String> outputTypesForProcessorAndOs(
            String processor, String os) {
        Set<String> archs = architecturesForProcessor(processor);
        List<String> result = new ArrayList<>();
        for (String ot : ALL_OUTPUT_TYPES) {
            try {
                OsDefinition def = JsonLoader.load(os, ot);
                if (def.variants != null) {
                    for (OsDefinition.Variant v : def.variants) {
                        if (v.architecture != null
                                && archs.contains(v.architecture.toLowerCase())) {
                            result.add(ot);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) { /* no definition for this combo */ }
        }
        return result;
    }

    // ── event handlers ────────────────────────────────────────────────────────

    /**
     * Processor changed → reload processor description, repopulate OS
     * choices and clear downstream fields.
     */
    private void onProcessorChanged() {
        // clear downstream
        currentDef = null;
        osDescArea.setText("");
        osChoice.removeAllItems();
        osChoice.addItem("");
        outputTypeChoice.removeAllItems();
        outputTypeChoice.addItem("");
        variantDescArea.setText("");
        variantChoice.removeAllItems();
        variantChoice.addItem("");

        String processor = selectedText(processorChoice);
        if (processor.isEmpty()) {
            processorDescArea.setText("");
            refreshOkButton();
            return;
        }

        // show processor description
        try {
            ProcessorDefinition def = JsonLoader.loadProcessor(processor);
            processorDescArea.setText(buildProcessorDescription(def));
            processorDescArea.setCaretPosition(0);
        } catch (Exception ex) {
            processorDescArea.setText(
                    "Could not load processor definition for '" + processor + "':\n"
                    + ex.getMessage());
        }

        // populate OS choices filtered by processor
        Set<String> compatibleOses = osesForProcessor(processor);
        for (String os : ALL_OSES) {
            if (compatibleOses.contains(os)) {
                osChoice.addItem(os);
            }
        }

        refreshOkButton();
    }

    /**
     * OS changed → load OS definition overview, repopulate output-type
     * choices and clear downstream fields.
     */
    private void onOsChanged() {
        currentDef = null;
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

        // show OS description from the first loadable definition
        try {
            OsDefinition firstDef = JsonLoader.load(os);
            osDescArea.setText(buildOsDescription(firstDef));
            osDescArea.setCaretPosition(0);
        } catch (Exception ex) {
            osDescArea.setText("Could not load definition for " + os + ":\n"
                    + ex.getMessage());
        }

        // populate output types filtered by processor + OS
        String processor = selectedText(processorChoice);
        List<String> validTypes = outputTypesForProcessorAndOs(processor, os);
        for (String ot : validTypes) {
            outputTypeChoice.addItem(ot);
        }

        refreshOkButton();
    }

    /**
     * Output type changed → load OS definition for this OS + output type,
     * repopulate variant choices filtered by the processor's architecture.
     */
    private void onOutputTypeChanged() {
        currentDef = null;
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

        // populate variant choices filtered by processor architecture
        Set<String> archs = architecturesForProcessor(selectedText(processorChoice));
        if (currentDef.variants != null) {
            for (OsDefinition.Variant v : currentDef.variants) {
                if (v.architecture != null
                        && archs.contains(v.architecture.toLowerCase())) {
                    variantChoice.addItem(v.name != null ? v.name : v.id);
                }
            }
        }
        refreshOkButton();
    }

    /**
     * Variant changed → show variant description.
     */
    private void onVariantChanged() {
        String selected = selectedText(variantChoice);
        if (selected.isEmpty() || currentDef == null || currentDef.variants == null) {
            variantDescArea.setText("");
            refreshOkButton();
            return;
        }

        // find the matching variant in currentDef (by displayed name)
        OsDefinition.Variant matched = null;
        for (OsDefinition.Variant v : currentDef.variants) {
            String display = v.name != null ? v.name : v.id;
            if (selected.equals(display)) {
                matched = v;
                break;
            }
        }

        if (matched != null) {
            variantDescArea.setText(buildVariantDescription(matched));
            variantDescArea.setCaretPosition(0);
        } else {
            variantDescArea.setText("");
        }

        refreshOkButton();
    }

    /** Enables OK only when all five fields are non-blank and the name is valid. */
    private void refreshOkButton() {
        // auto-populate variant name when all combos are filled and user hasn't typed
        maybePopulateDefaultName();

        String name = variantNameField.getText().trim();
        boolean nameOk = !name.isEmpty() && name.matches(VARIANT_NAME_PATTERN);
        boolean ready = nameOk
                     && !selectedText(osChoice).isEmpty()
                     && !selectedText(outputTypeChoice).isEmpty()
                     && !selectedText(variantChoice).isEmpty()
                     && !selectedText(processorChoice).isEmpty();
        okBtn.setEnabled(ready);
    }

    /**
     * When all four combo boxes have a non-blank selection and the variant
     * name field is still empty (and the user has not manually edited it),
     * populates the name with a default derived from the selected options.
     * <p>Format: {@code <os>-<processor>-<bits>-<linking>}, e.g.
     * {@code linux-x86_64-64-static}.</p>
     */
    private void maybePopulateDefaultName() {
        if (userEditedName) return;
        if (!variantNameField.getText().trim().isEmpty()) return;

        String processor  = selectedText(processorChoice);
        String os         = selectedText(osChoice);
        String outputType = selectedText(outputTypeChoice);
        String variant    = selectedText(variantChoice);
        if (processor.isEmpty() || os.isEmpty()
                || outputType.isEmpty() || variant.isEmpty()) {
            return;
        }

        // Derive bits and linking from the selected variant definition
        String bits    = "";
        String linking = "";
        if (currentDef != null && currentDef.variants != null) {
            for (OsDefinition.Variant v : currentDef.variants) {
                String display = v.name != null ? v.name : v.id;
                if (variant.equals(display)) {
                    if (v.bits > 0) bits = String.valueOf(v.bits);
                    if (v.linking != null) linking = v.linking.toLowerCase();
                    break;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(os.toLowerCase());
        sb.append('-').append(processor.toLowerCase());
        if (!bits.isEmpty())    sb.append('-').append(bits);
        if (!linking.isEmpty()) sb.append('-').append(linking);

        // Set the text without triggering the userEditedName flag
        settingNameProgrammatically = true;
        variantNameField.setText(sb.toString());
        settingNameProgrammatically = false;
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
     * The cascade order is: Processor → OS → Output Type → Variant.
     */
    private void applyPreFill(ProjectFile.VariantEntry ve) {
        if (ve.variantName != null) variantNameField.setText(ve.variantName);

        // Select Processor → triggers onProcessorChanged (which populates OS)
        if (ve.processor != null && selectItem(processorChoice, ve.processor)) {
            onProcessorChanged();
        }

        // Select OS → triggers onOsChanged (which populates output types)
        if (ve.os != null && selectItem(osChoice, ve.os)) {
            onOsChanged();
        }

        // Select Output Type → triggers onOutputTypeChanged (which populates variants)
        if (ve.outputType != null && selectItem(outputTypeChoice, ve.outputType)) {
            onOutputTypeChanged();
        }

        // Select Variant → triggers onVariantChanged
        if (ve.variant != null) selectItem(variantChoice, ve.variant);
        onVariantChanged();

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
