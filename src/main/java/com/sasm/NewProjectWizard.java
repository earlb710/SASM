package com.sasm;

import java.awt.*;
import java.awt.event.*;

/**
 * "New Project" dialog — single-canvas layout.
 *
 * <p>All three steps live on one scrollable canvas, stacked vertically.
 * Step 2 appears below Step 1 once an OS is chosen; Step 3 appears below
 * Step 2 once an executable variant is chosen.</p>
 *
 * <ul>
 *   <li>Step 1 – OS selection ({@link Choice} pop-list).</li>
 *   <li>Step 2 – Executable-format variant selection (loaded from the OS JSON).</li>
 *   <li>Step 3 – Component viewer: a scrollable list of required components with a
 *       detail panel showing description, fields table, and NASM code example.</li>
 * </ul>
 */
public class NewProjectWizard extends Dialog {

    // ── step 1 widgets ───────────────────────────────────────────────────────
    private final Choice osChoice = new Choice();

    // ── step 2 widgets ───────────────────────────────────────────────────────
    private final Choice variantChoice = new Choice();
    private final Label  variantDesc   = new Label("", Label.LEFT);

    // ── step 3 widgets ───────────────────────────────────────────────────────
    private final java.awt.List componentList = new java.awt.List(10, false);
    private final TextArea detailArea = new TextArea("", 14, 60,
                                                      TextArea.SCROLLBARS_VERTICAL_ONLY);

    // ── bottom buttons ───────────────────────────────────────────────────────
    private final Button finishBtn = new Button("Finish");
    private final Button cancelBtn = new Button("Cancel");

    // ── section panels (shown/hidden as user progresses) ─────────────────────
    private Panel step2Section;
    private Panel step3Section;

    // ── scrollable canvas ────────────────────────────────────────────────────
    private Panel   canvas;
    private ScrollPane scrollPane;

    // ── runtime state ────────────────────────────────────────────────────────
    private OsDefinition currentDef;

    public NewProjectWizard(Frame owner) {
        super(owner, "New Project", true /* modal */);
        buildUi();
        pack();
        setResizable(true);
        setMinimumSize(new Dimension(720, 500));
        setLocationRelativeTo(owner);
    }

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
        titlePanel.setPreferredSize(new Dimension(720, 36));
        add(titlePanel, BorderLayout.NORTH);

        // ── single scrollable canvas ─────────────────────────────────────────
        canvas = new Panel(new GridBagLayout());
        canvas.setBackground(new Color(0xF8, 0xF9, 0xFA));

        step2Section = buildStep2Section();
        step3Section = buildStep3Section();
        step2Section.setVisible(false);
        step3Section.setVisible(false);

        GridBagConstraints gc = canvasGbc(0);
        canvas.add(buildStep1Section(), gc);
        gc = canvasGbc(1);
        canvas.add(step2Section, gc);
        gc = canvasGbc(2);
        canvas.add(step3Section, gc);

        // Spacer row — absorbs all remaining vertical space and keeps steps pinned to top
        GridBagConstraints spacer = canvasGbc(3);
        spacer.weighty = 1.0;
        spacer.fill = GridBagConstraints.BOTH;
        canvas.add(new Panel(), spacer);

        scrollPane = new ScrollPane(ScrollPane.SCROLLBARS_AS_NEEDED);
        scrollPane.add(canvas);
        scrollPane.setPreferredSize(new Dimension(720, 460));
        add(scrollPane, BorderLayout.CENTER);

        // ── button row ──────────────────────────────────────────────────────
        finishBtn.setEnabled(false);
        Panel btnRow = new Panel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        btnRow.add(finishBtn);
        btnRow.add(cancelBtn);
        add(btnRow, BorderLayout.SOUTH);

        // ── wire events ─────────────────────────────────────────────────────
        osChoice.addItemListener(e -> onOsSelected());
        variantChoice.addItemListener(e -> onVariantSelected());
        componentList.addItemListener(e -> refreshComponentDetail());
        finishBtn.addActionListener(e -> dispose());
        cancelBtn.addActionListener(e -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dispose(); }
        });
    }

    // ── section builders ──────────────────────────────────────────────────────

    private Panel buildStep1Section() {
        Panel section = new Panel(new BorderLayout(0, 0));
        section.add(sectionHeader("Step 1 – Select target operating system"),
                    BorderLayout.NORTH);

        Panel body = new Panel(new GridBagLayout());
        body.setBackground(Color.WHITE);
        GridBagConstraints c = bodyGbc(0);
        osChoice.addItem("Linux");
        osChoice.addItem("Windows");
        body.add(osChoice, c);

        c = bodyGbc(1);
        Label hint = new Label("The wizard will load the matching OS executable-format definition.");
        hint.setForeground(Color.DARK_GRAY);
        body.add(hint, c);

        section.add(body,    BorderLayout.CENTER);
        section.add(divider(), BorderLayout.SOUTH);
        return section;
    }

    private Panel buildStep2Section() {
        Panel section = new Panel(new BorderLayout(0, 0));
        section.add(sectionHeader("Step 2 – Select executable variant"),
                    BorderLayout.NORTH);

        Panel body = new Panel(new GridBagLayout());
        body.setBackground(Color.WHITE);
        GridBagConstraints c = bodyGbc(0);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        body.add(variantChoice, c);

        c = bodyGbc(1);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        variantDesc.setForeground(new Color(0x33, 0x33, 0x33));
        body.add(variantDesc, c);

        section.add(body,    BorderLayout.CENTER);
        section.add(divider(), BorderLayout.SOUTH);
        return section;
    }

    private Panel buildStep3Section() {
        Panel section = new Panel(new BorderLayout(0, 0));
        section.add(sectionHeader("Step 3 – Explore required components"),
                    BorderLayout.NORTH);

        // left: component list
        Panel leftPanel = new Panel(new BorderLayout(4, 4));
        leftPanel.setBackground(Color.WHITE);
        Label listLabel = new Label("Components  (* = required):");
        listLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        leftPanel.add(listLabel, BorderLayout.NORTH);
        leftPanel.add(componentList, BorderLayout.CENTER);
        leftPanel.setPreferredSize(new Dimension(260, 300));

        // right: detail area
        detailArea.setEditable(false);
        detailArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        Panel rightPanel = new Panel(new BorderLayout(4, 4));
        rightPanel.setBackground(Color.WHITE);
        Label detLabel = new Label("Details:");
        detLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        rightPanel.add(detLabel, BorderLayout.NORTH);
        rightPanel.add(detailArea, BorderLayout.CENTER);

        Panel body = new Panel(new BorderLayout(8, 8));
        body.setBackground(Color.WHITE);
        Panel inset = new Panel(new BorderLayout(8, 8));
        inset.setBackground(Color.WHITE);
        inset.add(leftPanel,  BorderLayout.WEST);
        inset.add(rightPanel, BorderLayout.CENTER);
        body.add(inset, BorderLayout.CENTER);

        section.add(body, BorderLayout.CENTER);
        return section;
    }

    // ── event handlers ────────────────────────────────────────────────────────

    /** Called when the OS choice changes — loads JSON, shows step 2. */
    private void onOsSelected() {
        // If OS changes after step 2/3 are shown, reset downstream panels
        step3Section.setVisible(false);
        finishBtn.setEnabled(false);

        if (!loadOsDefinition()) return;
        populateVariants();

        step2Section.setVisible(true);
        revalidateCanvas();
        scrollToBottom();
    }

    /** Called when the variant choice changes — populates components, shows step 3. */
    private void onVariantSelected() {
        refreshVariantDescription();
        populateComponents();

        step3Section.setVisible(true);
        finishBtn.setEnabled(true);
        revalidateCanvas();
        scrollToBottom();
    }

    private void revalidateCanvas() {
        canvas.invalidate();
        canvas.validate();
        scrollPane.validate();
        validate();
    }

    private void scrollToBottom() {
        // Scroll the vertical scrollbar to the maximum to reveal the new section
        Adjustable vbar = scrollPane.getVAdjustable();
        vbar.setValue(vbar.getMaximum());
    }

    // ── data helpers ─────────────────────────────────────────────────────────

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
        variantDesc.setText(truncate(desc, 140));
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
        if (vidx >= currentDef.variants.size()) return;
        OsDefinition.Variant v = currentDef.variants.get(vidx);
        if (v.required_components == null || cidx >= v.required_components.size()) return;
        OsDefinition.Component comp = v.required_components.get(cidx);
        detailArea.setText(buildComponentDetail(comp));
        detailArea.setCaretPosition(0);
    }

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
        if (comp.fields != null && !comp.fields.isEmpty()) {
            sb.append("Fields:\n");
            sb.append(String.format("  %-22s %-8s %-6s %-20s %s\n",
                                    "Name", "Offset", "Size", "Value", "Note"));
            sb.append("  ").append("-".repeat(80)).append('\n');
            for (OsDefinition.Field f : comp.fields) {
                sb.append(String.format("  %-22s %-8s %-6s %-20s %s\n",
                        safe(f.name), safe(f.offset),
                        f.size_bytes, safe(f.value), safe(f.note)));
            }
            sb.append('\n');
        }
        if (comp.code_example != null && comp.code_example.source != null) {
            sb.append("Code example (").append(comp.code_example.language).append("):\n");
            sb.append(comp.code_example.source).append('\n');
        }
        return sb.toString();
    }

    // ── visual helpers ────────────────────────────────────────────────────────

    /** Light blue section-header bar with bold label. */
    private static Panel sectionHeader(String text) {
        Label lbl = new Label("  " + text, Label.LEFT);
        lbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        lbl.setForeground(new Color(0x2B, 0x57, 0x97));
        Panel p = new Panel(new BorderLayout());
        p.setBackground(new Color(0xDC, 0xE8, 0xF5));
        p.add(lbl, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(0, 28));
        return p;
    }

    /** 1-pixel horizontal divider line. */
    private static Panel divider() {
        Panel p = new Panel();
        p.setBackground(new Color(0xC0, 0xC8, 0xD8));
        p.setPreferredSize(new Dimension(0, 1));
        return p;
    }

    /** GBC for a top-level section row on the canvas. */
    private static GridBagConstraints canvasGbc(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.weighty = 0.0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 0, 0);
        return c;
    }

    /** GBC for a body row inside a section. */
    private static GridBagConstraints bodyGbc(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(row == 0 ? 10 : 4, 14, 8, 14);
        return c;
    }

    private static String safe(String s) { return s != null ? s : ""; }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (maxLen <= 1) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
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

