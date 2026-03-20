package com.sasm;

import java.awt.*;
import java.awt.event.*;

/**
 * Multi-step "New Project" wizard implemented with AWT components.
 *
 * <p>Step 1 – OS selection (Choice pop-list).<br>
 * Step 2 – Executable-format variant selection (loaded from the OS JSON).<br>
 * Step 3 – Component viewer: a scrollable list of required components with a
 *           detail panel that shows the description, fields table, and NASM code
 *           example for the selected component.</p>
 */
public class NewProjectWizard extends Dialog {

    // ── layout ──────────────────────────────────────────────────────────────
    private final CardLayout cards = new CardLayout();
    private final Panel      deck  = new Panel(cards);

    // ── step 1 ──────────────────────────────────────────────────────────────
    private final Choice osChoice = new Choice();

    // ── step 2 ──────────────────────────────────────────────────────────────
    private final Choice variantChoice = new Choice();
    private final Label  variantDesc   = new Label("", Label.LEFT);

    // ── step 3 ──────────────────────────────────────────────────────────────
    private final java.awt.List componentList  = new java.awt.List(10, false);
    private final TextArea detailArea  = new TextArea("", 12, 60,
                                                       TextArea.SCROLLBARS_VERTICAL_ONLY);

    // ── navigation buttons ───────────────────────────────────────────────────
    private final Button backBtn   = new Button("< Back");
    private final Button nextBtn   = new Button("Next >");
    private final Button finishBtn = new Button("Finish");
    private final Button cancelBtn = new Button("Cancel");

    // ── runtime state ────────────────────────────────────────────────────────
    private OsDefinition currentDef;
    private int currentStep = 1;   // 1, 2, or 3

    // Card names
    private static final String STEP1 = "step1";
    private static final String STEP2 = "step2";
    private static final String STEP3 = "step3";

    public NewProjectWizard(Frame owner) {
        super(owner, "New Project", true /* modal */);
        buildUi();
        pack();
        setResizable(true);
        setMinimumSize(new Dimension(680, 480));
        // centre on owner
        setLocationRelativeTo(owner);
    }

    // ────────────────────────────────────────────────────────────────────────
    // UI construction
    // ────────────────────────────────────────────────────────────────────────

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));

        // ── title banner ────────────────────────────────────────────────────
        Label title = new Label("New Project", Label.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setBackground(new Color(0x2B, 0x57, 0x97));
        title.setForeground(Color.WHITE);
        Panel titlePanel = new Panel(new BorderLayout());
        titlePanel.add(title, BorderLayout.CENTER);
        titlePanel.setPreferredSize(new Dimension(680, 36));
        add(titlePanel, BorderLayout.NORTH);

        // ── card deck ───────────────────────────────────────────────────────
        deck.add(buildStep1Panel(), STEP1);
        deck.add(buildStep2Panel(), STEP2);
        deck.add(buildStep3Panel(), STEP3);
        add(deck, BorderLayout.CENTER);

        // ── button row ──────────────────────────────────────────────────────
        Panel btnRow = new Panel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        backBtn.setEnabled(false);
        finishBtn.setEnabled(false);
        btnRow.add(backBtn);
        btnRow.add(nextBtn);
        btnRow.add(finishBtn);
        btnRow.add(cancelBtn);
        add(btnRow, BorderLayout.SOUTH);

        // ── wire up events ───────────────────────────────────────────────────
        nextBtn.addActionListener(e -> advance());
        backBtn.addActionListener(e -> retreat());
        finishBtn.addActionListener(e -> dispose());
        cancelBtn.addActionListener(e -> dispose());

        variantChoice.addItemListener(e -> refreshVariantDescription());
        componentList.addItemListener(e -> refreshComponentDetail());

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });
    }

    private Panel buildStep1Panel() {
        Panel p = new Panel(new GridBagLayout());
        GridBagConstraints c = defaultGbc();

        c.gridy = 0;
        p.add(new Label("Step 1 of 3 – Select target operating system:"), c);

        c.gridy = 1; c.insets = new Insets(8, 12, 4, 12);
        osChoice.addItem("Linux");
        osChoice.addItem("Windows");
        p.add(osChoice, c);

        c.gridy = 2; c.insets = new Insets(4, 12, 4, 12);
        Label hint = new Label(
                "The wizard will load the matching OS executable-format definition.");
        hint.setForeground(Color.DARK_GRAY);
        p.add(hint, c);

        return p;
    }

    private Panel buildStep2Panel() {
        Panel p = new Panel(new GridBagLayout());
        GridBagConstraints c = defaultGbc();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;

        c.gridy = 0;
        p.add(new Label("Step 2 of 3 – Select executable variant:"), c);

        c.gridy = 1; c.insets = new Insets(8, 12, 4, 12);
        p.add(variantChoice, c);

        c.gridy = 2;
        c.insets = new Insets(4, 12, 4, 12);
        variantDesc.setForeground(new Color(0x33, 0x33, 0x33));
        p.add(variantDesc, c);

        return p;
    }

    private Panel buildStep3Panel() {
        Panel p = new Panel(new BorderLayout(6, 6));
        p.add(new Label("Step 3 of 3 – Select a component to view its details:"),
              BorderLayout.NORTH);

        // left: component list
        Panel leftPanel = new Panel(new BorderLayout(4, 4));
        leftPanel.add(new Label("Components  (* = required):"), BorderLayout.NORTH);
        leftPanel.add(componentList, BorderLayout.CENTER);
        leftPanel.setPreferredSize(new Dimension(240, 300));

        // right: detail area
        detailArea.setEditable(false);
        detailArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        Panel rightPanel = new Panel(new BorderLayout(4, 4));
        rightPanel.add(new Label("Details:"), BorderLayout.NORTH);
        rightPanel.add(detailArea, BorderLayout.CENTER);

        Panel splitPanel = new Panel(new BorderLayout(6, 6));
        splitPanel.add(leftPanel,  BorderLayout.WEST);
        splitPanel.add(rightPanel, BorderLayout.CENTER);
        p.add(splitPanel, BorderLayout.CENTER);

        // Wrap with uniform 8px inset on all sides
        Panel wrapper = new Panel(new BorderLayout());
        wrapper.add(p, BorderLayout.CENTER);
        // top/bottom/left/right spacers
        Panel top   = new Panel(); top.setPreferredSize(new Dimension(1, 8));
        Panel left  = new Panel(); left.setPreferredSize(new Dimension(8, 1));
        Panel right = new Panel(); right.setPreferredSize(new Dimension(8, 1));
        wrapper.add(top,   BorderLayout.NORTH);
        wrapper.add(left,  BorderLayout.WEST);
        wrapper.add(right, BorderLayout.EAST);
        return wrapper;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Navigation
    // ────────────────────────────────────────────────────────────────────────

    private void advance() {
        if (currentStep == 1) {
            if (!loadOsDefinition()) return;
            populateVariants();
            showStep(2);
        } else if (currentStep == 2) {
            populateComponents();
            showStep(3);
        }
    }

    private void retreat() {
        if (currentStep == 3) showStep(2);
        else if (currentStep == 2) showStep(1);
    }

    private void showStep(int step) {
        currentStep = step;
        switch (step) {
            case 1 -> { cards.show(deck, STEP1); backBtn.setEnabled(false);
                        nextBtn.setEnabled(true); finishBtn.setEnabled(false); }
            case 2 -> { cards.show(deck, STEP2); backBtn.setEnabled(true);
                        nextBtn.setEnabled(true); finishBtn.setEnabled(false);
                        refreshVariantDescription(); }
            case 3 -> { cards.show(deck, STEP3); backBtn.setEnabled(true);
                        nextBtn.setEnabled(false); finishBtn.setEnabled(true); }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Data loading helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Returns true on success, false if loading failed (error already shown). */
    private boolean loadOsDefinition() {
        String os = osChoice.getSelectedItem();
        try {
            currentDef = JsonLoader.load(os);
            return true;
        } catch (Exception ex) {
            showError("Could not load JSON for " + os + ":\n" + ex.getMessage());
            return false;
        }
    }

    private void populateVariants() {
        variantChoice.removeAll();
        if (currentDef == null || currentDef.variants == null) return;
        for (OsDefinition.Variant v : currentDef.variants) {
            variantChoice.addItem(v.name != null ? v.name : v.id);
        }
        refreshVariantDescription();
    }

    private void refreshVariantDescription() {
        if (currentDef == null || currentDef.variants == null) return;
        int idx = variantChoice.getSelectedIndex();
        if (idx < 0 || idx >= currentDef.variants.size()) return;
        OsDefinition.Variant v = currentDef.variants.get(idx);
        String desc = v.description != null ? v.description : "";
        variantDesc.setText(truncate(desc, 120));
    }

    private void populateComponents() {
        componentList.removeAll();
        detailArea.setText("");
        int idx = variantChoice.getSelectedIndex();
        if (currentDef == null || currentDef.variants == null) return;
        if (idx < 0 || idx >= currentDef.variants.size()) return;
        OsDefinition.Variant v = currentDef.variants.get(idx);
        if (v.required_components == null) return;
        for (OsDefinition.Component comp : v.required_components) {
            componentList.add(comp.toString());
        }
        if (componentList.getItemCount() > 0) {
            componentList.select(0);
            refreshComponentDetail();
        }
    }

    private void refreshComponentDetail() {
        int cidx = componentList.getSelectedIndex();
        int vidx = variantChoice.getSelectedIndex();
        if (currentDef == null || cidx < 0 || vidx < 0) return;
        OsDefinition.Variant v = currentDef.variants.get(vidx);
        if (v.required_components == null || cidx >= v.required_components.size()) return;
        OsDefinition.Component comp = v.required_components.get(cidx);
        detailArea.setText(buildComponentDetail(comp));
        detailArea.setCaretPosition(0);
    }

    /** Builds the plain-text detail string shown in the right-hand TextArea. */
    private static String buildComponentDetail(OsDefinition.Component comp) {
        StringBuilder sb = new StringBuilder();

        sb.append("Component: ").append(comp.name).append('\n');
        sb.append("Required : ").append(comp.required ? "Yes" : "No").append('\n');
        sb.append("Size     : ").append(comp.size_bytes).append(" bytes\n");
        if (comp.offset_in_file != null)
            sb.append("Offset   : ").append(comp.offset_in_file).append('\n');
        sb.append('\n');

        if (comp.description != null && !comp.description.isBlank()) {
            sb.append("Description:\n  ")
              .append(comp.description.replace("\n", "\n  "))
              .append("\n\n");
        }

        // fields table
        if (comp.fields != null && !comp.fields.isEmpty()) {
            sb.append("Fields:\n");
            sb.append(String.format("  %-22s %-8s %-6s %-20s %s\n",
                                    "Name", "Offset", "Size", "Value", "Note"));
            sb.append("  " + "-".repeat(80)).append('\n');
            for (OsDefinition.Field f : comp.fields) {
                sb.append(String.format("  %-22s %-8s %-6s %-20s %s\n",
                        safe(f.name), safe(f.offset),
                        f.size_bytes, safe(f.value), safe(f.note)));
            }
            sb.append('\n');
        }

        // code example
        if (comp.code_example != null && comp.code_example.source != null) {
            sb.append("Code example (").append(comp.code_example.language).append("):\n");
            sb.append(comp.code_example.source).append('\n');
        }

        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Utilities
    // ────────────────────────────────────────────────────────────────────────

    private static GridBagConstraints defaultGbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0;
        c.fill  = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(12, 12, 4, 12);
        return c;
    }

    private static String safe(String s) { return s != null ? s : ""; }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    private void showError(String msg) {
        Dialog err = new Dialog(this, "Error", true);
        err.setLayout(new BorderLayout(8, 8));
        TextArea ta = new TextArea(msg, 6, 50, TextArea.SCROLLBARS_NONE);
        ta.setEditable(false);
        err.add(ta, BorderLayout.CENTER);
        Button ok = new Button("OK");
        ok.addActionListener(e2 -> err.dispose());
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
